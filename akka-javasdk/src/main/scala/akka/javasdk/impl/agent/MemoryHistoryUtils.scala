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
   * The start of the returned list moves on every turn once the window is full. See [[trimToStableWindow]] for a window
   * that does not.
   */
  def trimToLastN(messages: util.List[SessionMessage], lastN: Optional[Integer]): util.List[SessionMessage] = {
    if (lastN.isPresent && messages.size > lastN.get) {
      messages.subList(messages.size - lastN.get, messages.size)
    } else {
      messages
    }
  }

  /**
   * A block-aligned window when a low water mark is present, otherwise a sliding window.
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
   * Keep a suffix of the history that starts at an anchor which only moves in blocks. The anchor stays put until the
   * retained count would exceed `highWaterMark`, then jumps forward by `highWaterMark - lowWaterMark`, so the retained
   * count varies between the two marks and the messages before the anchor are the same on consecutive turns. Unlike
   * [[trimToLastN]], which drops the oldest message every turn and so changes the prompt every turn.
   *
   * The anchor is derived from the message count alone, so callers must pass the full history: a truncated list places
   * the anchor somewhere else.
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
      val blocks = ((total - highWaterMark) + block - 1) / block
      messages.subList(blocks * block, total)
    }
  }
}
