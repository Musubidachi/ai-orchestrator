package dev.fmangoni.forge.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import dev.fmangoni.forge.spec.model.FrontMatter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Renders a spec back to a wishlist Markdown file.
 *
 * <p>The front matter is emitted by Jackson rather than assembled from strings. Hand-rolling it
 * would work until the first title containing a colon — {@code title: Tool: does X} is not the YAML
 * anyone meant — and the failure would appear as a confusing parse error at load time, well away
 * from the form that produced it.
 */
@UtilityClass
public class SpecWriter {

    private final ObjectMapper YAML = new ObjectMapper(
            new YAMLFactory()
                    // Forge writes its own fences, since the body follows the closing one.
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    /** @return the file written, named {@code <id>-<slug>.md} */
    public Path write(Path wishlistDir, FrontMatter front, String body) {
        Path file = wishlistDir.resolve(fileName(front));
        try {
            Files.createDirectories(wishlistDir);
            Files.writeString(file, render(front, body), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write spec " + file, e);
        }
    }

    public String render(FrontMatter front, String body) {
        return "---\n" + yaml(front) + "---\n\n" + body.strip() + "\n";
    }

    private String yaml(FrontMatter front) {
        try {
            String out = YAML.writeValueAsString(front);
            return out.endsWith("\n") ? out : out + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialise front matter", e);
        }
    }

    public String fileName(FrontMatter front) {
        return slug(front.getId()) + "-" + slug(front.getTitle()) + ".md";
    }

    /** Lowercase, alphanumerics and single hyphens — safe on Windows and readable in a directory listing. */
    private String slug(String text) {
        if (text == null || text.isBlank()) {
            return "untitled";
        }
        String slug = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-$", "");
        }
        return slug.isBlank() ? "untitled" : slug;
    }
}
