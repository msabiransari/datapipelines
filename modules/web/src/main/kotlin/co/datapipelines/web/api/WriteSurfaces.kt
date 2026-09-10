package co.datapipelines.web.api

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.WriteSurface

/**
 * The entry-point mapping for the version tables' `*_via` stamps (V20, 102, versioning §3.7):
 * the REST surface a write arrives on is the PRINCIPAL's credential, read once here so no
 * controller re-derives it.
 *
 * `PROMOTION` maps to [WriteSurface.SESSION]: the promotion receiver is not a keyed human
 * surface, and its imports land through the repository's RELEASED paths, which keep the
 * database default — the mapping exists so the exhaustive `when` cannot silently drift when a
 * new auth method appears.
 */
fun AuthenticatedPrincipal.writeSurface(): WriteSurface =
    when (authMethod) {
        AuthMethod.OIDC -> WriteSurface.SESSION
        AuthMethod.API_KEY -> WriteSurface.API_KEY
        AuthMethod.PROMOTION -> WriteSurface.SESSION
    }
