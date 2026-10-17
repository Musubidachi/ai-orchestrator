package dev.fmangoni.forge.security.osv;

import dev.fmangoni.forge.security.osv.model.OsvQuery;
import dev.fmangoni.forge.security.osv.model.OsvVuln;
import java.io.IOException;
import java.util.List;

/**
 * Known vulnerabilities for packages, from OSV (api.osv.dev).
 *
 * <p>What leaves this machine is a package name, its ecosystem and a version — never source,
 * never paths, never the project's name. Chosen because nothing needs installing and no key is
 * involved, which matters on a machine where Forge must never hold a credential.
 */
public interface OsvClient {

    /** For each query, in order, the vulnerabilities affecting it. */
    List<List<OsvVuln>> lookup(List<OsvQuery> queries) throws IOException;

    /** The public service. */
    static OsvClient http() {
        return new OsvHttpClient();
    }

}
