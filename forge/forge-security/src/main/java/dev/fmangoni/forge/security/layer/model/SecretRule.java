package dev.fmangoni.forge.security.layer.model;

import java.util.regex.Pattern;

public record SecretRule(String name, Pattern pattern, String severity, boolean serious) {
}
