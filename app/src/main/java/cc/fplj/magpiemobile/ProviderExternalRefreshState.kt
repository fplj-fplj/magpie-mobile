package cc.fplj.magpiemobile

/** The external-editor revision observed when an actual list read starts. */
internal data class ProviderExternalRefreshTicket(val session: ProviderSessionKey, val revision: Int) {
    fun isCurrent(currentSession: ProviderSessionKey?, currentRevision: Int): Boolean =
        session == currentSession && revision == currentRevision

    /** Owners, connection epochs, and return revisions only increase within their owner. */
    internal fun supersedes(other: ProviderExternalRefreshTicket): Boolean =
        session.owner > other.session.owner ||
            (session.owner == other.session.owner && session.epoch > other.session.epoch) ||
            (session == other.session && revision > other.revision)
}

/**
 * Attempting a revision does not confirm it. A failed automatic read must await an explicit
 * retry, while a later external return remains pending even if an older read is still busy.
 * UI-operation sequence checks remain necessary before confirming a same-revision retry.
 */
internal data class ProviderExternalRefreshState(
    val attempted: ProviderExternalRefreshTicket? = null,
    val confirmed: ProviderExternalRefreshTicket? = null,
) {
    fun needsAttempt(session: ProviderSessionKey?, revision: Int): Boolean {
        if (session == null || revision <= 0) return false
        val ticket = ProviderExternalRefreshTicket(session, revision)
        return attempted != ticket && attempted?.supersedes(ticket) != true
    }

    /** Merely observing a return while an editor/request is active never consumes that return. */
    fun automaticTicket(
        enabled: Boolean,
        session: ProviderSessionKey?,
        revision: Int,
        editorOpen: Boolean,
        busy: Boolean,
    ): ProviderExternalRefreshTicket? =
        if (!enabled || session == null || editorOpen || busy || !needsAttempt(session, revision)) null
        else ProviderExternalRefreshTicket(session, revision)

    fun isConfirmed(session: ProviderSessionKey?, revision: Int): Boolean =
        session != null && confirmed?.isCurrent(session, revision) == true

    fun needsConfirmation(session: ProviderSessionKey?, revision: Int): Boolean =
        session != null && revision > 0 && !isConfirmed(session, revision)

    /** Call only after the panel's synchronous read gates and session/sequence checks pass. */
    fun readStarted(
        ticket: ProviderExternalRefreshTicket,
        currentSession: ProviderSessionKey?,
        currentRevision: Int,
    ): ProviderExternalRefreshState =
        if (!ticket.isCurrent(currentSession, currentRevision) || attempted?.supersedes(ticket) == true) this
        else copy(attempted = ticket)

    /** Call only for a healthy result after checking the panel's current UI-operation sequence. */
    fun readConfirmed(
        ticket: ProviderExternalRefreshTicket,
        currentSession: ProviderSessionKey?,
        currentRevision: Int,
    ): ProviderExternalRefreshState =
        if (attempted != ticket || !ticket.isCurrent(currentSession, currentRevision)) this
        else copy(confirmed = ticket)
}
