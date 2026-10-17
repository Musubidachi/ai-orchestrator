package dev.fmangoni.forge.security.layer.model;

import java.util.Set;
import java.util.regex.Pattern;

public record PatternRule(Set<String> extensions, Pattern pattern, String title, String severity) {
}
