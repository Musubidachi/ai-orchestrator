package dev.fmangoni.forge.ui.pictures;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** A submitted picture review: one batch, and what the person said about each picture. */
@Data
public class RuleRequest {

    private String id;

    private List<RuleItem> rulings = new ArrayList<>();
}
