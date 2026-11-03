package madoyomi.core.repo

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * How a repo's index is read (`EXTENSIONSTORE.format`).
 *
 * - [AUTO]: upstream's behaviour: sniff the content and follow a `repo.json` `index_v2` redirect.
 * - [JSON]: always parse the JSON index (for example yuzono's `index.min.json`), never follow `index_v2`.
 * - [PROTOBUF]: always parse a protobuf `index.pb`.
 */
enum class RepoIndexFormat {
    AUTO,
    JSON,
    PROTOBUF,
}
