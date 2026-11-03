package madoyomi.core.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import graphql.ErrorType
import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import io.github.oshai.kotlinlogging.KotlinLogging
import madoyomi.core.error.ErrorClassifier
import madoyomi.core.error.ErrorCode
import java.util.concurrent.CompletableFuture

/**
 * The GraphQL side of the error model: every error in `errors[]` carries `extensions = {code, details}`.
 *
 * - [exceptionHandler] replaces upstream's, which appended the stack trace to the message. A resolver's exception is
 *   classified by [ErrorClassifier]; the stack trace of an unexpected one ([ErrorCode.INTERNAL]) goes to the log.
 * - [instrumentation] gives the errors graphql-java makes itself (syntax, validation) `code = BAD_REQUEST`.
 */
object MadoyomiGraphQLErrors {
    private val logger = KotlinLogging.logger {}

    const val CODE = "code"
    const val DETAILS = "details"

    val exceptionHandler =
        DataFetcherExceptionHandler { parameters ->
            val exception = parameters.exception
            val classified = ErrorClassifier.classify(exception)
            if (classified.expected) {
                logger.debug { "GraphQL field ${parameters.path} failed (${classified.code}): ${classified.message}" }
            } else {
                logger.error(exception) { "GraphQL field ${parameters.path} failed" }
            }
            val error =
                GraphqlErrorBuilder
                    .newError()
                    .message(classified.message)
                    .path(parameters.path)
                    .location(parameters.sourceLocation)
                    .errorType(ErrorType.DataFetchingException)
                    .extensions(mapOf(CODE to classified.code.name, DETAILS to classified.details.ifEmpty { null }))
                    .build()
            CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
        }

    val instrumentation =
        object : SimplePerformantInstrumentation() {
            override fun instrumentExecutionResult(
                executionResult: ExecutionResult,
                parameters: InstrumentationExecutionParameters,
                state: InstrumentationState?,
            ): CompletableFuture<ExecutionResult> {
                val errors = executionResult.errors
                if (errors.all { it.extensions?.get(CODE) != null }) return CompletableFuture.completedFuture(executionResult)
                return CompletableFuture.completedFuture(executionResult.transform { it.errors(errors.map(::withCode)) })
            }
        }

    private fun withCode(error: GraphQLError): GraphQLError {
        if (error.extensions?.get(CODE) != null) return error
        val code =
            when (error.errorType) {
                ErrorType.InvalidSyntax, ErrorType.ValidationError, ErrorType.OperationNotSupported -> ErrorCode.BAD_REQUEST
                else -> ErrorCode.INTERNAL
            }
        return GraphqlErrorBuilder
            .newError()
            .message(error.message)
            .locations(error.locations)
            .apply { error.path?.let { path(it) } }
            .errorType(error.errorType)
            .extensions(error.extensions.orEmpty() + mapOf(CODE to code.name, DETAILS to null))
            .build()
    }
}
