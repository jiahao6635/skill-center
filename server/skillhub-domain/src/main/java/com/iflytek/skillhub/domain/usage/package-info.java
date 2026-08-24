/**
 * Usage-ledger domain: append-only skill usage events, lifetime unique actors,
 * and the single recorder that writes them. Public download counts stay on the
 * skill aggregate; this package answers "who used which skill, when, from which
 * client". Must not depend on servlet, Redis, auth, or storage.
 */
package com.iflytek.skillhub.domain.usage;
