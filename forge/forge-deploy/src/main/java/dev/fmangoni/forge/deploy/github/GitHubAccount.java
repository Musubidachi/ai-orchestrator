package dev.fmangoni.forge.deploy.github;

import java.util.Optional;

/**
 * The person's GitHub account, as their own logged-in CLI reaches it.
 *
 * <p>An interface so publishing can be tested without a network or an account. The one real
 * implementation is {@link GitHubCli}, and nothing here ever sees a token: git asks the CLI for one
 * through {@link #credentialHelper()}.
 */
public interface GitHubAccount {

    /** The account's login, or empty when the CLI is not logged in. */
    Optional<String> login();

    /** The web address of {@code repository}, written {@code owner/name}, or empty when it does not exist. */
    Optional<String> urlOf(String repository);

    /** Makes a private repository called {@code name} on the account, and returns its web address. */
    String createPrivate(String name, String description);

    /** What git is told to run for the account's credentials. */
    String credentialHelper();
}
