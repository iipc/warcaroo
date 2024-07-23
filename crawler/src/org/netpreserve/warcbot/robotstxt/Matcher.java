// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.netpreserve.warcbot.robotstxt;

import org.netpreserve.warcbot.util.Url;

import java.util.List;

/** Interface of a matcher class. */
public interface Matcher {
  /**
   * Check whether at least one of given user agents is allowed to visit given URL based on
   * robots.txt which this matcher represents.
   *
   * @param userAgents interested user agents
   * @param url target URL
   * @return {@code true} iff verdict is ALLOWED
   */
  boolean allowedByRobots(final List<String> userAgents, final String url);

  boolean allowedByRobots(List<String> userAgents, Url url);

  /**
   * Check whether given user agent is allowed to visit given URL based on robots.txt which this
   * matcher represents.
   *
   * @param userAgent interested user agent
   * @param url target URL
   * @return {@code true} iff verdict is ALLOWED
   */
  boolean singleAgentAllowedByRobots(final String userAgent, final String url);

  /**
   * Check whether at least one of given user agents is allowed to visit given URL based on
   * robots.txt which this matcher represents. All global rule groups are ignored.
   *
   * @param userAgents interested user agents
   * @param url target URL
   * @return {@code true} iff verdict is ALLOWED
   */
  boolean ignoreGlobalAllowedByRobots(final List<String> userAgents, final String url);
}
