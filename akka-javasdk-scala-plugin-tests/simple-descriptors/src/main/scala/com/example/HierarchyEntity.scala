/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example

import akka.javasdk.annotations.Component

@Component(id = "hierarchy-entity")
class HierarchyEntity extends AbstractInBetweenEntity[String] {}
