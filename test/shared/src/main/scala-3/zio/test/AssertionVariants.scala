/*
 * Copyright 2019-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.{ErrorMessage => M}
import zio.test.Assertion.Arguments.valueArgument

trait AssertionVariants {

  /**
   * Makes a new assertion that requires a value equal the specified value.
   */
  final def equalTo[A](expected: A): Assertion[A] =
    Assertion[A](
      TestArrow
        .make[A, Boolean] { actual =>
          val result = (actual, expected) match {
            case (left: Array[_], right: Array[_])         => left.sameElements[Any](right)
            case (left: CharSequence, right: CharSequence) => left.toString == right.toString
            case (left, right)                             => left == right
          }
          TestTrace.boolean(result) {
            M.pretty(actual) + M.equals + M.pretty(expected)
          }
        }
        .withCode("equalTo", valueArgument(expected))
    )
}
