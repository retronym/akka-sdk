/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.javasdk.impl.agent

import java.util
import java.util.Optional
import java.util.function.Function

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import akka.annotation.InternalApi
import akka.javasdk.agent.AgentRegistry
import akka.javasdk.agent.MemoryFilter
import akka.javasdk.agent.SessionMessage

/**
 * INTERNAL API
 *
 * Shared filtering and trimming helpers for session memory history. Used by both `SessionMemoryEntity#getHistory` (when
 * reading the current entity state) and the agent runtime (when falling back to a chunked journal read), so the two
 * paths produce identical context for the model.
 *
 * The public API uses Java collection types so the entity (which is implemented in Java) can call it without conversion
 * noise; Scala callers convert at the call site.
 */
@InternalApi
object MemoryHistoryUtils {

  /** Resolves a component id to its role, or `Optional.empty` if the agent has no usable role. */
  type RoleLookup = Function[String, Optional[String]]

  /**
   * Resolve the role of an agent component, treating null/blank roles as "no role" so a role-based filter does not
   * accidentally match every agent.
   */
  def roleLookup(agentRegistry: AgentRegistry): RoleLookup =
    (componentId: String) =>
      agentRegistry
        .agentInfoOption(componentId)
        .flatMap(info => Optional.ofNullable(info.role()).filter(r => r.trim.nonEmpty))

  /** Apply each filter in order, narrowing the message list as we go. */
  def applyFilters(
      messages: util.List[SessionMessage],
      filters: util.List[MemoryFilter],
      roleLookup: RoleLookup): util.List[SessionMessage] = {
    var current = messages.asScala.toList
    filters.asScala.foreach { filter =>
      current = applyFilter(current, filter, roleLookup)
    }
    current.asJava
  }

  private def applyFilter(
      messages: List[SessionMessage],
      filter: MemoryFilter,
      roleLookup: RoleLookup): List[SessionMessage] = filter match {
    case incl: MemoryFilter.Include =>
      val ids = incl.ids().asScala
      val roles = incl.roles().asScala
      messages.filter { m =>
        ids.contains(m.componentId()) ||
        roleLookup.apply(m.componentId()).toScala.exists(roles.contains)
      }
    case excl: MemoryFilter.Exclude =>
      val ids = excl.ids().asScala
      val roles = excl.roles().asScala
      messages.filter { m =>
        !ids.contains(m.componentId()) &&
        !roleLookup.apply(m.componentId()).toScala.exists(roles.contains)
      }
  }

  /**
   * Keep only the last `n` messages. When `lastN` is empty or the list already has at most `n` elements, the input list
   * is returned untouched.
   *
   * Note that this drops one message per turn once the window is full, so the start of the prompt moves on every turn
   * and the provider's prompt cache never hits. Prefer [[trimToStableWindow]] where the model supports prompt caching.
   */
  def trimToLastN(messages: util.List[SessionMessage], lastN: Optional[Integer]): util.List[SessionMessage] = {
    if (lastN.isPresent && messages.size > lastN.get) {
      messages.subList(messages.size - lastN.get, messages.size)
    } else {
      messages
    }
  }

  /**
   * Apply whichever windowing the memory settings ask for: a block-aligned window when a low water mark is present,
   * otherwise the sliding window.
   */
  def trim(
      messages: util.List[SessionMessage],
      highWaterMark: Optional[Integer],
      lowWaterMark: Optional[Integer]): util.List[SessionMessage] = {
    if (highWaterMark.isPresent && lowWaterMark.isPresent)
      trimToStableWindow(messages, highWaterMark.get, lowWaterMark.get)
    else
      trimToLastN(messages, highWaterMark)
  }

  /**
   * Keep a suffix of the history that starts at an anchor which only moves in blocks.
   *
   * A sliding window ([[trimToLastN]]) drops the oldest message on every turn, so every turn sends a different prefix
   * to the model and no prompt cache can ever hit. Here the anchor stays where it is until the retained count would
   * exceed `highWaterMark`, and then jumps forward by one block of `highWaterMark - lowWaterMark`. The retained count
   * therefore varies between the two marks, and the prefix is byte-identical for as many turns as there are messages in
   * a block.
   *
   * The anchor is a function of the message count alone, so it needs no stored state and every caller that sees the
   * same history computes the same window. Callers must pass the full history for that reason: a caller that passes a
   * truncated list computes a different anchor and defeats the purpose.
   */
  def trimToStableWindow(
      messages: util.List[SessionMessage],
      highWaterMark: Int,
      lowWaterMark: Int): util.List[SessionMessage] = {
    require(highWaterMark > lowWaterMark, s"highWaterMark [$highWaterMark] must exceed lowWaterMark [$lowWaterMark]")
    require(lowWaterMark >= 0, s"lowWaterMark [$lowWaterMark] must not be negative")

    val total = messages.size
    if (total <= highWaterMark) messages
    else {
      val block = highWaterMark - lowWaterMark
      // Smallest whole number of blocks that brings the retained count back under the high mark.
      val blocks = ((total - highWaterMark) + block - 1) / block
      messages.subList(blocks * block, total)
    }
  }
}
