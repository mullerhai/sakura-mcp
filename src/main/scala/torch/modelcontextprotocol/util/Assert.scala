/*
 * Copyright 2024-2024 the original author or authors.
 */
package torch.modelcontextprotocol.util

import reactor.util.annotation.Nullable

import java.util

/**
 * Assertion utility class that assists in validating arguments.
 *
 * @author Christian Tzolov
 */

/**
 * Utility class providing assertion methods for parameter validation.
 */
object Assert {
  /**
   * Assert that the collection is not {@code null} and not empty.
   *
   * @param collection the collection to check
   * @param message    the exception message to use if the assertion fails
   * @throws IllegalArgumentException if the collection is {@code null} or empty
   */
  def notEmpty(@Nullable collection: util.Collection[?], message: String): Unit = {
    if (collection == null || collection.isEmpty) throw new IllegalArgumentException(message)
  }

  /**
   * Assert that an object is not {@code null}.
   *
   * <pre class="code">
   * Assert.notNull(clazz, "The class must not be null");
   * </pre>
   *
   * @param object  the object to check
   * @param message the exception message to use if the assertion fails
   * @throws IllegalArgumentException if the object is {@code null}
   */
  def notNull(@Nullable `object`: AnyRef, message: String): Unit = {
    if (`object` == null) throw new IllegalArgumentException(message)
  }

  /**
   * Assert that the given String contains valid text content; that is, it must not be
   * {@code null} and must contain at least one non-whitespace character.
   * <pre class="code">Assert.hasText(name, "'name' must not be empty");</pre>
   *
   * @param text    the String to check
   * @param message the exception message to use if the assertion fails
   * @throws IllegalArgumentException if the text does not contain valid text content
   */
  def hasText(@Nullable text: String, message: String): Unit = {
    if (!hasText(text)) throw new IllegalArgumentException(message)
  }

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
  def hasText(@Nullable str: String): Boolean = str != null && !(str.isEmpty)
}