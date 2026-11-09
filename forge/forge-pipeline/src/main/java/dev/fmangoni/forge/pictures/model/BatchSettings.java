package dev.fmangoni.forge.pictures.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A batch's {@code batch.json}: what to call it, where its list is, and who draws for it.
 *
 * @param title  what the dashboard calls the batch
 * @param cards  the job list, relative to the batch folder
 * @param rules  what a person checks each picture against, relative to the batch folder, or null
 * @param anyway what is added to a placing command when the kept picture's check found a problem, or null
 * @param target the project the pictures are for, as the dashboard names it, or null
 * @param agent  who draws: it names the prompt file in {@code repo/} and the dashboard's button
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchSettings(String title, String cards, String rules, String anyway, String target, String agent) {

    public BatchSettings {
        title = title == null || title.isBlank() ? "Pictures" : title;
        cards = cards == null || cards.isBlank() ? "job-cards.md" : cards;
        agent = agent == null || agent.isBlank() ? "Astra" : agent;
    }
}
