package ai.kukuvaia.agents

import ai.kukuvaia.provider.model.TaskComplexity

/**
 * Declares the complexity level of an Embabel {@code @Action} method.
 * Used by the model routing system to automatically select the optimal
 * model tier for the action (e.g., EXTRACTION → worker/Haiku).
 *
 * When used with {@code withAutoModel()}, the framework resolves:
 *   @ActionComplexity → TaskComplexity → role mapping (DB) → ChatModel
 *
 * If no mapping exists in DB, falls back to [TaskComplexity.defaultRole].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ActionComplexity(val value: TaskComplexity)
