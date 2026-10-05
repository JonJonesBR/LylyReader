use regex::Regex;
use std::sync::OnceLock;
use once_cell::sync::Lazy;
use super::{common_preprocess, split_sentences_generic, LanguageNormalizer};

pub struct KoreanNormalizer;

pub static KOREAN_NORMALIZER: Lazy<KoreanNormalizer> = Lazy::new(|| KoreanNormalizer);

impl LanguageNormalizer for KoreanNormalizer {
    fn preprocess(&self, text: &str) -> String {
        // Korean skips the ending punctuation check, but does all other common cleanups
        common_preprocess(text)
    }

    fn split_sentences(&self, text: &str) -> Vec<String> {
        static KOREAN_SENTENCE_RE: OnceLock<Regex> = OnceLock::new();
        let re = KOREAN_SENTENCE_RE.get_or_init(|| Regex::new(r"([.!?]['\u{2019}\u{201D}\u{0022}\)\}\]]?)\s+").unwrap());
        split_sentences_generic(text, re, &[])
    }

    fn max_chunk_len(&self) -> usize {
        120
    }

    fn should_wrap_tags(&self) -> bool {
        true
    }
}
