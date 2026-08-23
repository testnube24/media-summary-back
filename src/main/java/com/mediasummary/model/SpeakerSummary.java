package com.mediasummary.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** What one participant of the audio contributed, as summarized by the model. */
@AllArgsConstructor
@Getter
public class SpeakerSummary {

    private final String speaker;
    private final String summary;
}
