package dev.fmangoni.forge.report.evidence;

import java.nio.file.Path;
import java.util.List;

/**
 * @param label files directly under {@code evidence/} are grouped under an empty label, which
 *              is what runs made before evidence was split by session look like
 */
public record EvidenceGroup(String label, List<Path> files) {
}
