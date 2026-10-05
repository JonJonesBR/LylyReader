use regex::Regex;
use std::sync::OnceLock;
use std::collections::BTreeMap;
use once_cell::sync::Lazy;
use serde::Deserialize;
use super::{common_preprocess, check_and_add_ending_punctuation, split_sentences_generic, LanguageNormalizer};

#[derive(Deserialize)]
struct HindiConfig {
    replacements: BTreeMap<String, String>,
}

static HINDI_CONFIG: Lazy<HindiConfig> = Lazy::new(|| {
    let json_str = include_str!("configs/hi.json");
    serde_json::from_str(json_str).expect("Failed to parse hi.json")
});

pub struct HindiNormalizer;

pub static HINDI_NORMALIZER: Lazy<HindiNormalizer> = Lazy::new(|| HindiNormalizer);

impl LanguageNormalizer for HindiNormalizer {
    fn preprocess(&self, text: &str) -> String {
        let mut text = text.to_string();
        for (from, to) in &HINDI_CONFIG.replacements {
            text = text.replace(from, to);
        }
        
        let cleaned = common_preprocess(&text);
        check_and_add_ending_punctuation(cleaned)
    }

    fn split_sentences(&self, text: &str) -> Vec<String> {
        static HINDI_SENTENCE_RE: OnceLock<Regex> = OnceLock::new();
        let re = HINDI_SENTENCE_RE.get_or_init(|| Regex::new(r"([.!?\u{0964}\u{0965}]['\u{2019}\u{201D}\u{0022}\)\}\]]?)\s+").unwrap());
        split_sentences_generic(text, re, &[])
    }

    fn max_chunk_len(&self) -> usize {
        300
    }

    fn should_wrap_tags(&self) -> bool {
        true
    }
}
