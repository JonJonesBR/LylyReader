use regex::Regex;
use std::sync::OnceLock;
use std::collections::BTreeMap;
use once_cell::sync::Lazy;
use serde::Deserialize;
use super::{common_preprocess, check_and_add_ending_punctuation, split_sentences_generic, LanguageNormalizer};

#[derive(Deserialize)]
struct FrenchConfig {
    replacements: BTreeMap<String, String>,
    abbreviations: Vec<String>,
}

static FRENCH_CONFIG: Lazy<FrenchConfig> = Lazy::new(|| {
    let json_str = include_str!("configs/fr.json");
    serde_json::from_str(json_str).expect("Failed to parse fr.json")
});

pub struct FrenchNormalizer;

pub static FRENCH_NORMALIZER: Lazy<FrenchNormalizer> = Lazy::new(|| FrenchNormalizer);

impl LanguageNormalizer for FrenchNormalizer {
    fn preprocess(&self, text: &str) -> String {
        let mut text = text.to_string();
        
        // French guillemets (handling both with and without spaces)
        text = text.replace("« ", "\"").replace(" »", "\"");
        text = text.replace('«', "\"").replace('»', "\"");
        
        // French-specific punctuation spacing (non-breaking spaces or regular spaces before ? ! : ;)
        text = text.replace('\u{00A0}', " ").replace('\u{202F}', " ");
        
        // Prevent common_preprocess from ruining French punctuation spacing
        // Remove spaces BEFORE French punctuation marks to prevent fusing sentences together
        static PUNC_SPACE_RE: OnceLock<Regex> = OnceLock::new();
        let punc_space_re = PUNC_SPACE_RE.get_or_init(|| Regex::new(r"\s+([!?:;])\s+").unwrap());
        text = punc_space_re.replace_all(&text, "$1 ").to_string();

        static PUNC_END_RE: OnceLock<Regex> = OnceLock::new();
        let punc_end_re = PUNC_END_RE.get_or_init(|| Regex::new(r"\s+([!?:;])$").unwrap());
        text = punc_end_re.replace_all(&text, "$1").to_string();
        
        for (from, to) in &FRENCH_CONFIG.replacements {
            text = text.replace(from, to);
        }
        
        let cleaned = common_preprocess(&text);
        check_and_add_ending_punctuation(cleaned)
    }

    fn split_sentences(&self, text: &str) -> Vec<String> {
        static FRENCH_SENTENCE_RE: OnceLock<Regex> = OnceLock::new();
        let re = FRENCH_SENTENCE_RE.get_or_init(|| Regex::new(r"([.!?]['\u{2019}\u{201D}\u{0022}\)\}\]]?)\s+").unwrap());
        
        let abbrev_refs: Vec<&str> = FRENCH_CONFIG.abbreviations.iter().map(|s| s.as_str()).collect();
        split_sentences_generic(text, re, &abbrev_refs)
    }

    fn max_chunk_len(&self) -> usize {
        300
    }

    fn should_wrap_tags(&self) -> bool {
        true
    }
}
