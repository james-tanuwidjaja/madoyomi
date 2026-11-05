package madoyomi.core.graphql

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import madoyomi.core.extension.ExtensionCompatLog

/** One distinct API gap of an extension ([ExtensionCompatLog.Issue]), for `ExtensionType.compatIssues`. */
data class ExtensionCompatIssueType(
    val kind: ExtensionCompatLog.IssueKind,
    @GraphQLDescription("The missing class or member, or the stubbed method (a.b.C.method)")
    val target: String,
    @GraphQLDescription("RUNTIME: hit by the extension's code; STATIC: found by the install-time link check")
    val origin: ExtensionCompatLog.Origin,
    @GraphQLDescription("Where it was seen first: a source operation (popular, videos, load, ...) or, for STATIC, a jar class")
    val context: String?,
    @GraphQLDescription("How often it was seen")
    val count: Long,
    @GraphQLDescription("Epoch milliseconds")
    val firstSeen: Long,
    @GraphQLDescription("Epoch milliseconds")
    val lastSeen: Long,
) {
    constructor(issue: ExtensionCompatLog.Issue) : this(
        kind = issue.kind,
        target = issue.target,
        origin = issue.origin,
        context = issue.context,
        count = issue.count,
        firstSeen = issue.firstSeen.toEpochMilli(),
        lastSeen = issue.lastSeen.toEpochMilli(),
    )

    companion object {
        fun of(pkg: String): List<ExtensionCompatIssueType> = ExtensionCompatLog.issues(pkg).map(::ExtensionCompatIssueType)
    }
}
