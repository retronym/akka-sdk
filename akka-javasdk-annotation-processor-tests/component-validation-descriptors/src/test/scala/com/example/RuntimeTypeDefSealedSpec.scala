/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example

import akka.javasdk.validation.ast.runtime.RuntimeTypeDef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// A Scala 2.x sealed trait: compiles to a plain JVM interface (no ACC_SEALED).
// RuntimeTypeDef.isSealed() must detect it via Scala runtime reflection.
sealed trait ScalaSealedEvent
case class EventA() extends ScalaSealedEvent
case class EventB() extends ScalaSealedEvent

// A Scala non-sealed trait for the negative case.
trait NonSealedScalaEvent

class RuntimeTypeDefSealedSpec extends AnyWordSpec with Matchers {

  "RuntimeTypeDef.isSealed" should {

    "return true for Scala 2.x sealed traits (no JVM ACC_SEALED attribute)" in {
      // Verify the JVM itself does NOT see it as sealed (pre-condition for the test to be meaningful)
      classOf[ScalaSealedEvent].isSealed shouldBe false

      new RuntimeTypeDef(classOf[ScalaSealedEvent]).isSealed() shouldBe true
    }

    "return false for Scala non-sealed traits" in {
      new RuntimeTypeDef(classOf[NonSealedScalaEvent]).isSealed() shouldBe false
    }
  }
}
