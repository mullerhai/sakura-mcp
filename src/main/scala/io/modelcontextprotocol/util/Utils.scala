/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.util

import reactor.util.annotation.Nullable

import java.util

/**
 * Miscellaneous utility methods.
 *
 * @author Christian Tzolov
 */
object Utils {
  /**
   * Check whether the given {@code String} contains actual <em>text</em>.
   * <p>
   * More specifically, this method returns {@code true} if the {@code String} is not
   * {@code null}, its length is greater than 0, and it contains at least one
   * non-whitespace character.
   *
   * @param str the {@code String} to check (may be {@code null})
   * @return {@code true} if the {@code String} is not {@code null}, its length is
   *         greater than 0, and it does not contain whitespace only
   * @see Character#isWhitespace
   */
  def hasText(@Nullable str: String): Boolean = str != null && !(str.isBlank)

  /**
   * Return {@code true} if the supplied Collection is {@code null} or empty. Otherwise,
   * return {@code false}.
   *
   * @param collection the Collection to check
   * @return whether the given Collection is empty
   */
  def isEmpty(@Nullable collection: util.Collection[_]): Boolean = collection == null || collection.isEmpty

  /**
   * Return {@code true} if the supplied Map is {@code null} or empty. Otherwise, return
   * {@code false}.
   *
   * @param map the Map to check
   * @return whether the given Map is empty
   */
  def isEmpty(@Nullable map: util.Map[_, _]): Boolean = map == null || map.isEmpty
}