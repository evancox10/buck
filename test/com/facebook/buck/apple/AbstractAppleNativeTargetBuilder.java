/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public abstract class AbstractAppleNativeTargetBuilder<
    ARG extends AppleNativeTargetDescriptionArg,
    BUILDER extends AbstractAppleNativeTargetBuilder<ARG, BUILDER>>
    extends AbstractNodeBuilder<ARG> {

  public AbstractAppleNativeTargetBuilder(
      Description<ARG> description,
      BuildTarget target) {
    super(description, target);
  }

  public BUILDER setConfigs(
      Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs) {
    arg.configs = configs;
    return getThis();
  }

  public BUILDER setCompilerFlags(Optional<ImmutableList<String>> compilerFlags) {
    arg.compilerFlags = compilerFlags;
    return getThis();
  }

  public BUILDER setPlatformCompilerFlags(
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformPreprocessorFlags) {
    arg.platformPreprocessorFlags = platformPreprocessorFlags;
    return getThis();
  }

  public BUILDER setPreprocessorFlags(Optional<ImmutableList<String>> preprocessorFlags) {
    arg.preprocessorFlags = preprocessorFlags;
    return getThis();
  }

  public BUILDER setPlatformPreprocessorFlags(
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformPreprocessorFlags) {
    arg.platformPreprocessorFlags = platformPreprocessorFlags;
    return getThis();
  }

  public BUILDER setLangPreprocessorFlags(
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> langPreprocessorFlags) {
    arg.langPreprocessorFlags = langPreprocessorFlags;
    return getThis();
  }

  public BUILDER setExportedPreprocessorFlags(
      Optional<ImmutableList<String>> exportedPreprocessorFlags) {
    arg.exportedPreprocessorFlags = exportedPreprocessorFlags;
    return getThis();
  }

  public BUILDER setLinkerFlags(Optional<ImmutableList<String>> linkerFlags) {
    arg.linkerFlags = linkerFlags;
    return getThis();
  }

  public BUILDER setLinkerFlags(ImmutableList<String> linkerFlags) {
    return setLinkerFlags(Optional.of(linkerFlags));
  }

  public BUILDER setExportedLinkerFlags(Optional<ImmutableList<String>> exportedLinkerFlags) {
    arg.exportedLinkerFlags = exportedLinkerFlags;
    return getThis();
  }

  public BUILDER setSrcs(Optional<ImmutableSortedSet<SourceWithFlags>> srcs) {
    arg.srcs = srcs;
    return getThis();
  }

  public BUILDER setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    return setSrcs(Optional.of(srcs));
  }

  public BUILDER setExtraXcodeSources(Optional<ImmutableList<SourcePath>> extraXcodeSources) {
    arg.extraXcodeSources = extraXcodeSources;
    return getThis();
  }

  public BUILDER setHeaders(Optional<SourceList> headers) {
    arg.headers = headers;
    return getThis();
  }

  public BUILDER setHeaders(ImmutableSortedSet<SourcePath> headers) {
    return setHeaders(Optional.of(SourceList.ofUnnamedSources(headers)));
  }

  public BUILDER setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    return setHeaders(Optional.of(SourceList.ofNamedSources(headers)));
  }

  public BUILDER setExportedHeaders(Optional<SourceList> exportedHeaders) {
    arg.exportedHeaders = exportedHeaders;
    return getThis();
  }

  public BUILDER setExportedHeaders(ImmutableSortedSet<SourcePath> exportedHeaders) {
    return setExportedHeaders(Optional.of(SourceList.ofUnnamedSources(exportedHeaders)));
  }

  public BUILDER setExportedHeaders(ImmutableSortedMap<String, SourcePath> exportedHeaders) {
    return setExportedHeaders(Optional.of(SourceList.ofNamedSources(exportedHeaders)));
  }

  public BUILDER setFrameworks(Optional<ImmutableSortedSet<FrameworkPath>> frameworks) {
    arg.frameworks = frameworks;
    return getThis();
  }

  public BUILDER setLibraries(Optional<ImmutableSortedSet<FrameworkPath>> libraries) {
    arg.libraries = libraries;
    return getThis();
  }

  public BUILDER setDeps(Optional<ImmutableSortedSet<BuildTarget>> deps) {
    arg.deps = deps;
    return getThis();
  }

  public BUILDER setExportedDeps(Optional<ImmutableSortedSet<BuildTarget>> exportedDeps) {
    arg.exportedDeps = exportedDeps;
    return getThis();
  }

  public BUILDER setHeaderPathPrefix(Optional<String> headerPathPrefix) {
    arg.headerPathPrefix = headerPathPrefix;
    return getThis();
  }

  public BUILDER setPrefixHeader(Optional<SourcePath> prefixHeader) {
    arg.prefixHeader = prefixHeader;
    return getThis();
  }

  public BUILDER setTests(Optional<ImmutableSortedSet<BuildTarget>> tests) {
    arg.tests = tests;
    return getThis();
  }

  protected abstract BUILDER getThis();
}
