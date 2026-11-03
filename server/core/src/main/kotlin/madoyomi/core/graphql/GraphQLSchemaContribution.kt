package madoyomi.core.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.TopLevelObject
import java.util.ServiceLoader

/**
 * Top-level GraphQL objects a higher module adds to upstream's schema (`TachideskGraphQLSchema.kt`). Madoyomi's API
 * additions live in the `api` module, which `core` can't see, so they are found with [ServiceLoader]
 * (`META-INF/services/madoyomi.core.graphql.GraphQLSchemaContribution`). Koin would not do: schema generation starts
 * before Koin does (`GraphQLSchemaProvider.init()` runs before `applicationSetup()`, as upstream's `Main` has it).
 */
interface GraphQLSchemaContribution {
    val queries: List<Any> get() = emptyList()
    val mutations: List<Any> get() = emptyList()
    val subscriptions: List<Any> get() = emptyList()
}

/** Every [GraphQLSchemaContribution] on the classpath, as [TopLevelObject]s. */
object MadoyomiSchemaContributions {
    private val contributions: List<GraphQLSchemaContribution> by lazy {
        ServiceLoader.load(GraphQLSchemaContribution::class.java, GraphQLSchemaContribution::class.java.classLoader).toList()
    }

    fun queries(): List<TopLevelObject> = contributions.flatMap { it.queries }.map(::TopLevelObject)

    fun mutations(): List<TopLevelObject> = contributions.flatMap { it.mutations }.map(::TopLevelObject)

    fun subscriptions(): List<TopLevelObject> = contributions.flatMap { it.subscriptions }.map(::TopLevelObject)
}
