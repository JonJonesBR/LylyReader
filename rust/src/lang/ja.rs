use regex::Regex;
use std::sync::OnceLock;
use once_cell::sync::Lazy;
use super::{common_preprocess, check_and_add_ending_punctuation, split_sentences_generic, LanguageNormalizer};

pub struct JapaneseNormalizer;

pub static JAPANESE_NORMALIZER: Lazy<JapaneseNormalizer> = Lazy::new(|| JapaneseNormalizer);

impl LanguageNormalizer for JapaneseNormalizer {
    fn preprocess(&self, text: &str) -> String {
        let cleaned = common_preprocess(text);
        check_and_add_ending_punctuation(cleaned)
    }

    fn split_sentences(&self, text: &str) -> Vec<String> {
        static JAPANESE_SENTENCE_RE: OnceLock<Regex> = OnceLock::new();
        // Split on Japanese full stops or exclamation/question marks, with optional trailing spaces
        let re = JAPANESE_SENTENCE_RE.get_or_init(|| Regex::new(r"([。！？][」』）』）｝\]]?)\s*").unwrap());
        split_sentences_generic(text, re, &[])
    }

    fn max_chunk_len(&self) -> usize {
        120
    }

    fn should_wrap_tags(&self) -> bool {
        true
    }
}
