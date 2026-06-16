/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example

import akka.javasdk.annotations.http.HttpEndpoint
import akka.javasdk.annotations.http.Get

@HttpEndpoint("/hello")
class SomeEndpoint {
  @Get
  def hello(): String = "Hello from Scala"
}
