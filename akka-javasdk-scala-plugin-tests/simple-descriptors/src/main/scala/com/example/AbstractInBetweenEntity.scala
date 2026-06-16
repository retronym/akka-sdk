/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example

import akka.javasdk.keyvalueentity.KeyValueEntity

// Covers that the plugin correctly walks the hierarchy through intermediate base classes.
abstract class AbstractInBetweenEntity[S] extends KeyValueEntity[S] {}
