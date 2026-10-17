package dev.fmangoni.forge.security.osv.model;






















/** One package at one version. {@code ecosystem} is OSV's name: Maven, PyPI, npm. */
public record OsvQuery(String ecosystem, String name, String version) {
}
