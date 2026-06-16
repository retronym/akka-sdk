/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SimpleDescriptorSpec extends AnyWordSpec with Matchers {

  "akka-javasdk-components.conf" should {
    val config = ConfigFactory.load("META-INF/akka-javasdk-components_com.example_test.conf")

    "contain the http endpoint" in {
      val endpoints = config.getStringList("akka.javasdk.components.http-endpoint")
      endpoints.size() shouldBe 1
      endpoints should contain("com.example.SomeEndpoint")
    }

    "contain key-value entities, including one discovered through a hierarchy" in {
      val entities = config.getStringList("akka.javasdk.components.key-value-entity")
      entities.size() shouldBe 2
      entities should contain("com.example.SomeEntity")
      entities should contain("com.example.HierarchyEntity")
    }

    "contain the service setup class" in {
      config.getString("akka.javasdk.service-setup") shouldBe "com.example.SomeSetup"
    }
  }
}
