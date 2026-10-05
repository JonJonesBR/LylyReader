use regex::Regex;
use std::sync::OnceLock;
use super::{common_preprocess, check_and_add_ending_punctuation, split_sentences_generic, LanguageNormalizer};

pub struct DefaultNormalizer;

pub static DEFAULT_NORMALIZER: DefaultNormalizer = DefaultNormalizer;

impl LanguageNormalizer for DefaultNormalizer {
    fn preprocess(&self, text: &str) -> String {
        let cleaned = common_preprocess(text);
        check_and_add_ending_punctuation(cleaned)
    }

    fn split_sentences(&self, text: &str) -> Vec<String> {
        static DEFAULT_SENTENCE_RE: OnceLock<Regex> = OnceLock::new();
        let re = DEFAULT_SENTENCE_RE.get_or_init(|| Regex::new(r"([.!?]['\u{2019}\u{201D}\u{0022}\)\}\]]?)\s+").unwrap());
        split_sentences_generic(text, re, &[])
    }

    fn max_chunk_len(&self) -> usize {
        300
    }

    fn should_wrap_tags(&self) -> bool {
        true
    }
}
