package com.baseerat.vision.data

import com.baseerat.vision.R

/** Models the app can really run offline today. */
data class CatalogItem(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
    val url: String,
    val fileName: String,
    val kind: ModelKind
)

data class LinkItem(val titleRes: Int, val url: String)

object ModelCatalog {

    val items: List<CatalogItem> = listOf(
        CatalogItem(
            id = "tess_urd_fast",
            titleRes = R.string.cat_urd_fast_title,
            descRes = R.string.cat_urd_fast_desc,
            url = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/urd.traineddata",
            fileName = "urd.traineddata",
            kind = ModelKind.TESSERACT
        ),
        CatalogItem(
            id = "tess_eng_fast",
            titleRes = R.string.cat_eng_fast_title,
            descRes = R.string.cat_eng_fast_desc,
            url = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata",
            fileName = "eng.traineddata",
            kind = ModelKind.TESSERACT
        ),
        CatalogItem(
            id = "tess_ara_fast",
            titleRes = R.string.cat_ara_fast_title,
            descRes = R.string.cat_ara_fast_desc,
            url = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata",
            fileName = "ara.traineddata",
            kind = ModelKind.TESSERACT
        ),
        CatalogItem(
            id = "tess_urd_best",
            titleRes = R.string.cat_urd_best_title,
            descRes = R.string.cat_urd_best_desc,
            url = "https://github.com/tesseract-ocr/tessdata_best/raw/main/urd.traineddata",
            fileName = "urd.traineddata",
            kind = ModelKind.TESSERACT
        ),
        CatalogItem(
            id = "tflite_effnet0",
            titleRes = R.string.cat_effnet0_title,
            descRes = R.string.cat_effnet0_desc,
            url = "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite",
            fileName = "efficientnet_lite0.tflite",
            kind = ModelKind.TFLITE
        ),
        CatalogItem(
            id = "tflite_effnet2",
            titleRes = R.string.cat_effnet2_title,
            descRes = R.string.cat_effnet2_desc,
            url = "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite2/float32/1/efficientnet_lite2.tflite",
            fileName = "efficientnet_lite2.tflite",
            kind = ModelKind.TFLITE
        ),
        // ---- chat models (need the MediaPipe LLM engine inside the build) ----
        CatalogItem(
            id = "llm_qwen05",
            titleRes = R.string.cat_qwen05_title,
            descRes = R.string.cat_qwen05_desc,
            url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            kind = ModelKind.LLM
        ),
        CatalogItem(
            id = "llm_qwen15",
            titleRes = R.string.cat_qwen15_title,
            descRes = R.string.cat_qwen15_desc,
            url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            kind = ModelKind.LLM
        )
    )

    /** Web pages where more compatible files can be found (opened in the browser). */
    val links: List<LinkItem> = listOf(
        LinkItem(R.string.link_tessdata_fast, "https://github.com/tesseract-ocr/tessdata_fast"),
        LinkItem(R.string.link_tessdata_best, "https://github.com/tesseract-ocr/tessdata_best"),
        LinkItem(R.string.link_mediapipe_classifier, "https://ai.google.dev/edge/mediapipe/solutions/vision/image_classifier#models"),
        LinkItem(R.string.link_kaggle_tflite, "https://www.kaggle.com/models?framework=tfLite&task=image-classification")
    )

    /** Pages for chat models (.task files). Gemma needs a free licence acceptance in the browser. */
    val llmLinks: List<LinkItem> = listOf(
        LinkItem(R.string.link_litert_community, "https://huggingface.co/litert-community"),
        LinkItem(R.string.link_gemma3, "https://huggingface.co/litert-community/Gemma3-1B-IT"),
        LinkItem(R.string.link_mediapipe_llm, "https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference#models")
    )
}
