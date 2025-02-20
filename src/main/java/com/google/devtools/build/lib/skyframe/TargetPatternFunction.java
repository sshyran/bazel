// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.concurrent.MultisetSemaphore;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.AbstractRecursivePackageProvider.MissingDepException;
import com.google.devtools.build.lib.pkgcache.ParsingFailedEvent;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/**
 * TargetPatternFunction translates a target pattern (eg, "foo/...") into a set of resolved
 * Targets.
 */
public class TargetPatternFunction implements SkyFunction {

  public TargetPatternFunction() {
  }

  @Override
  public SkyValue compute(SkyKey key, Environment env) throws TargetPatternFunctionException,
      InterruptedException {
    TargetPatternValue.TargetPatternKey patternKey =
        ((TargetPatternValue.TargetPatternKey) key.argument());
    TargetPattern parsedPattern = patternKey.getParsedPattern();

    IgnoredPackagePrefixesValue ignoredPackagePrefixes =
        (IgnoredPackagePrefixesValue)
            env.getValue(IgnoredPackagePrefixesValue.key(parsedPattern.getRepository()));
    if (ignoredPackagePrefixes == null) {
      return null;
    }
    ImmutableSet<PathFragment> ignoredPatterns = ignoredPackagePrefixes.getPatterns();

    ResolvedTargets<Target> resolvedTargets;
    try {
      EnvironmentBackedRecursivePackageProvider provider =
          new EnvironmentBackedRecursivePackageProvider(env);
      RecursivePackageProviderBackedTargetPatternResolver resolver =
          new RecursivePackageProviderBackedTargetPatternResolver(
              provider,
              env.getListener(),
              patternKey.getPolicy(),
              MultisetSemaphore.unbounded(),
              SimplePackageIdentifierBatchingCallback::new);
      ImmutableSet<PathFragment> excludedSubdirectories = patternKey.getExcludedSubdirectories();
      ResolvedTargets.Builder<Target> resolvedTargetsBuilder = ResolvedTargets.builder();
      SafeBatchCallback<Target> callback =
          partialResult -> {
            for (Target target : partialResult) {
              // TODO(b/156899726): This will go away as soon as we remove implicit outputs from
              //  cc_library completely. The only downside to doing this is that implicit outputs
              //  won't be listed when doing somepackage:* for the handful of cases still on the
              //  allowlist. This is only a Google-internal problem and the scale of it is
              //  acceptable in the short term while cleaning up the allowlist.
              if (target instanceof OutputFile
                  && ((OutputFile) target)
                      .getGeneratingRule()
                      .getRuleClass()
                      .equals("cc_library")) {
                continue;
              }
              resolvedTargetsBuilder.add(target);
            }
          };
      parsedPattern.eval(
          resolver,
          () -> ignoredPatterns,
          excludedSubdirectories,
          callback,
          QueryExceptionMarkerInterface.MarkerRuntimeException.class);
      if (provider.encounteredPackageErrors()) {
        resolvedTargetsBuilder.setError();
      }
      resolvedTargets = resolvedTargetsBuilder.build();
    } catch (TargetParsingException e) {
      env.getListener().post(new ParsingFailedEvent(patternKey.getPattern(),  e.getMessage()));
      throw new TargetPatternFunctionException(e);
    } catch (MissingDepException e) {
      // The EnvironmentBackedRecursivePackageProvider constructed above might throw
      // MissingDepException to signal when it has a dependency on a missing Environment value.
      // Note that MissingDepException extends RuntimeException because the methods called
      // on EnvironmentBackedRecursivePackageProvider all belong to an interface shared with other
      // implementations that are unconcerned with MissingDepExceptions.
      return null;
    }
    Preconditions.checkNotNull(resolvedTargets, key);
    ResolvedTargets.Builder<Label> resolvedLabelsBuilder = ResolvedTargets.builder();
    if (resolvedTargets.hasError()) {
      resolvedLabelsBuilder.setError();
    }
    for (Target target : resolvedTargets.getTargets()) {
      resolvedLabelsBuilder.add(target.getLabel());
    }
    for (Target target : resolvedTargets.getFilteredTargets()) {
      resolvedLabelsBuilder.remove(target.getLabel());
    }
    return new TargetPatternValue(resolvedLabelsBuilder.build());
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by
   * {@link TargetPatternFunction#compute}.
   */
  private static final class TargetPatternFunctionException extends SkyFunctionException {
    public TargetPatternFunctionException(TargetParsingException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
