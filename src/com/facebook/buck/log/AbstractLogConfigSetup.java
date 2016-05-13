/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.log;

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.nio.file.Path;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractLogConfigSetup {
  private static final String DEFAULT_LOG_FILE_PREFIX = "buck-";

  public static final LogConfigSetup DEFAULT_SETUP = LogConfigSetup.builder().build();

  @Value.Default
  public boolean getRotateLog() {
    return false;
  }

  @Value.Default
  public int getCount() {
    return 25;
  }

  @Value.Default
  public String getLogFilePrefix() {
    return DEFAULT_LOG_FILE_PREFIX;
  }

  public Path getLogFilePath() {
    return BuckConstant.getLogPath().resolve(getLogFilePrefix() + "%g.log");
  }
}