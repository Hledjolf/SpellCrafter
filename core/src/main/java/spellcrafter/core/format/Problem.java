package spellcrafter.core.format;

/**
 * Something wrong with a document: a code, a JSON Pointer to the value at fault, and a message for people.
 *
 * <p>Codes: {@code not_json}, {@code invalid} (missing, or the wrong kind of value), {@code unknown_key},
 * {@code unknown_type} (a condition type with no dot that the vocabulary lacks), {@code too_many_decimals},
 * {@code not_whole}, {@code out_of_range}, {@code duplicate_id}, {@code bad_id}, {@code depth_exceeded},
 * {@code unsupported_format}, {@code unsupported_version}, {@code group_cycle}, {@code too_many_riders},
 * {@code unsupported_stage}, {@code mod_mismatch}; and the warning {@code cost_ignored}.
 */
public record Problem(String code, String pointer, String message) {
}
