use regex::Regex;
use std::sync::OnceLock;
use unicode_normalization::UnicodeNormalization;

mod default;
mod en;
mod fr;
mod hi;
mod ko;
mod ja;


pub trait LanguageNormalizer: Send + Sync {
    fn preprocess(&self, text: &str) -> String;
    fn split_sentences(&self, text: &str) -> Vec<String>;
    fn max_chunk_len(&self) -> usize;
    fn should_wrap_tags(&self) -> bool;
}

pub fn get_normalizer(lang: &str) -> &'static dyn LanguageNormalizer {
    match lang {
        "en" => &*en::ENGLISH_NORMALIZER,
        "fr" => &*fr::FRENCH_NORMALIZER,
        "hi" => &*hi::HINDI_NORMALIZER,
        "ko" => &*ko::KOREAN_NORMALIZER,
        "ja" => &*ja::JAPANESE_NORMALIZER,
        _ => &default::DEFAULT_NORMALIZER,
    }
}

// ============================================================================
// Shared pre-processing utility functions
// ============================================================================

#[derive(Debug, PartialEq, Clone, Copy)]
enum QuoteType {
    Opening,
    Closing,
    Ambiguous,
}

fn classify_quote(chars: &[char], i: usize) -> QuoteType {
    if chars.is_empty() || i >= chars.len() {
        return QuoteType::Ambiguous;
    }
    
    let has_before = i > 0;
    let has_after = i + 1 < chars.len();
    
    let char_before = if has_before { Some(chars[i - 1]) } else { None };
    let char_after = if has_after { Some(chars[i + 1]) } else { None };
    
    let before_is_ws = char_before.map_or(true, |c| c.is_whitespace());
    let after_is_ws = char_after.map_or(true, |c| c.is_whitespace());
    
    let before_is_open_punc = char_before.map_or(false, |c| "({,:-¿¡「『【〈《‹«".contains(c));
    let after_is_close_punc = char_after.map_or(false, |c| ")}.,!?:;-…。」』】〉》›»".contains(c));

    let is_opening = (before_is_ws || before_is_open_punc) && !after_is_ws;
    let is_closing = (after_is_ws || after_is_close_punc) && !before_is_ws;

    if is_opening && !is_closing {
        QuoteType::Opening
    } else if is_closing && !is_opening {
        QuoteType::Closing
    } else {
        QuoteType::Ambiguous
    }
}

pub fn remove_unmatched_quotes(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut to_remove = std::collections::HashSet::new();

    // 1. Process double quotes '"'
    let mut double_quotes = Vec::new();
    for (i, &c) in chars.iter().enumerate() {
        if c == '"' {
            double_quotes.push(i);
        }
    }

    if double_quotes.len() % 2 != 0 {
        let mut open_stack = Vec::new();
        for &idx in &double_quotes {
            let q_type = classify_quote(&chars, idx);
            match q_type {
                QuoteType::Opening => {
                    open_stack.push(idx);
                }
                QuoteType::Closing => {
                    if open_stack.is_empty() {
                        to_remove.insert(idx);
                    } else {
                        open_stack.pop();
                    }
                }
                QuoteType::Ambiguous => {
                    if open_stack.is_empty() {
                        open_stack.push(idx);
                    } else {
                        open_stack.pop();
                    }
                }
            }
        }
        for idx in open_stack {
            to_remove.insert(idx);
        }
    }

    // 2. Process single quotes '\''
    let mut single_quotes = Vec::new();
    for (i, &c) in chars.iter().enumerate() {
        if c == '\'' {
            let is_internal = if i > 0 && i + 1 < chars.len() {
                chars[i - 1].is_alphanumeric() && chars[i + 1].is_alphanumeric()
            } else {
                false
            };
            if !is_internal {
                single_quotes.push(i);
            }
        }
    }

    if single_quotes.len() % 2 != 0 {
        let mut open_stack = Vec::new();
        for &idx in &single_quotes {
            let q_type = classify_quote(&chars, idx);
            match q_type {
                QuoteType::Opening => {
                    open_stack.push(idx);
                }
                QuoteType::Closing => {
                    if open_stack.is_empty() {
                        to_remove.insert(idx);
                    } else {
                        open_stack.pop();
                    }
                }
                QuoteType::Ambiguous => {
                    if open_stack.is_empty() {
                        open_stack.push(idx);
                    } else {
                        open_stack.pop();
                    }
                }
            }
        }
        for idx in open_stack {
            to_remove.insert(idx);
        }
    }

    let mut result = String::new();
    for (i, &c) in chars.iter().enumerate() {
        if !to_remove.contains(&i) {
            result.push(c);
        }
    }
    result
}

pub fn common_preprocess(text: &str) -> String {
    let mut text: String = text.nfkd().collect();

    static EMOJI_RE: OnceLock<Regex> = OnceLock::new();
    let emoji_pattern = EMOJI_RE.get_or_init(|| Regex::new(r"[\x{1F600}-\x{1F64F}\x{1F300}-\x{1F5FF}\x{1F680}-\x{1F6FF}\x{1F700}-\x{1F77F}\x{1F780}-\x{1F7FF}\x{1F800}-\x{1F8FF}\x{1F900}-\x{1F9FF}\x{1FA00}-\x{1FA6F}\x{1FA70}-\x{1FAFF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}\x{1F1E6}-\x{1F1FF}]+").unwrap());
    text = emoji_pattern.replace_all(&text, "").to_string();

    let replacements = [
        ("–", "-"),
        ("‑", "-"),
        ("—", ", "),
        ("_", " "),
        ("\u{201C}", "\""),
        ("\u{201D}", "\""),
        ("\u{2018}", "'"),
        ("\u{2019}", "'"),
        ("´", "'"),
        ("`", "'"),
        ("[", " "),
        ("]", " "),
        ("|", " "),
        ("/", " "),
        ("#", " "),
        ("→", " "),
        ("←", " "),
    ];

    for (from, to) in &replacements {
        text = text.replace(from, to);
    }

    let special_symbols = ["♥", "☆", "♡", "©", "\\"];
    for symbol in &special_symbols {
        text = text.replace(symbol, "");
    }

    text = text.replace(" , ", ",");
    text = text.replace(" . ", ".");
    text = text.replace(" ! ", "!");
    text = text.replace(" ? ", "?");
    text = text.replace(" ; ", ";");
    text = text.replace(" : ", ":");
    text = text.replace(" ' ", "'");

    while text.contains("\"\"") {
        text = text.replace("\"\"", "\"");
    }
    while text.contains("''") {
        text = text.replace("''", "'");
    }
    while text.contains("``") {
        text = text.replace("``", "`");
    }

    static WHITESPACE_RE: OnceLock<Regex> = OnceLock::new();
    let whitespace_re = WHITESPACE_RE.get_or_init(|| Regex::new(r"\s+").unwrap());
    text = whitespace_re.replace_all(&text, " ").to_string();
    text = text.trim().to_string();

    remove_unmatched_quotes(&text)
}

pub fn split_sentences_generic(text: &str, punc_regex: &Regex, abbreviations: &[&str]) -> Vec<String> {
    let matches: Vec<_> = punc_regex.find_iter(text).collect();
    if matches.is_empty() {
        return vec![text.to_string()];
    }
    
    let mut sentences = Vec::new();
    let mut last_end = 0;
    
    for m in matches {
        let before_punc = &text[last_end..m.start()];
        let mut is_abbrev = false;
        let punc_char = m.as_str().chars().next().unwrap();
        for abbrev in abbreviations {
            let combined = format!("{}{}", before_punc.trim(), punc_char);
            if combined.ends_with(abbrev) {
                is_abbrev = true;
                break;
            }
        }
        
        if !is_abbrev {
            sentences.push(text[last_end..m.end()].to_string());
            last_end = m.end();
        }
    }
    
    if last_end < text.len() {
        sentences.push(text[last_end..].to_string());
    }
    
    if sentences.is_empty() {
        vec![text.to_string()]
    } else {
        sentences
    }
}

pub fn check_and_add_ending_punctuation(mut text: String) -> String {
    if !text.is_empty() {
        static ENDS_WITH_PUNCT_RE: OnceLock<Regex> = OnceLock::new();
        let ends_with_punct = ENDS_WITH_PUNCT_RE.get_or_init(|| Regex::new(r#"[.!?;:,'"\u{201C}\u{201D}\u{2018}\u{2019})\\\]}…。」』】〉》›»\u{0964}\u{0965}]$"#).unwrap());
        if !ends_with_punct.is_match(&text) {
            text.push('.');
        }
    }
    text
}

