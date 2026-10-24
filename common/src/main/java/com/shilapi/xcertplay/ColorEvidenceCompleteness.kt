package com.shilapi.xcertplay

internal object ColorEvidenceCompleteness {
    fun check(observed: Boolean, rendered: Boolean, sourceRecorded: Boolean, sourceReadable: Boolean,
        decoderRecorded: Boolean, outputRecorded: Boolean, captureRecorded: Boolean,
        softwareRequired: Boolean, softwareVerified: Boolean): List<String> = buildList {
        if (!observed) add("observation")
        if (!rendered) add("first_frame")
        if (!sourceRecorded) add("input_csd") else if (!sourceReadable) add("input_color_parse")
        if (!decoderRecorded) add("selected_decoder")
        if (!outputRecorded) add("output_format")
        if (!captureRecorded) add("capture")
        if (softwareRequired && !softwareVerified) add("software_decoder")
    }
}
