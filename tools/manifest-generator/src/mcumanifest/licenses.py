_COMMON_OSS = {
    "mit",
    "apache-2.0",
    "bsd-2-clause",
    "bsd-3-clause",
    "gpl-2.0-only",
    "gpl-3.0-only",
    "lgpl-2.1-only",
    "lgpl-3.0-only",
    "mpl-2.0",
    "cc0-1.0",
    "unlicense",
}

_DENIED_KEYWORDS = {"all rights reserved", "arr", "custom", "unknown"}


def hosted_allowed(license_value: str | None, allow_redistribution: bool = False) -> bool:
    """
    Return True if the license permits hosted redistribution.

    - Empty / missing values are always allowed.
    - Common open‑source identifiers are always allowed.
    - Explicitly denied values (ARR, All Rights Reserved, Custom, Unknown) and any
      unrecognised value are denied *unless* *allow_redistribution* is True.
    """
    if not license_value:
        return True
    value = license_value.strip()
    if not value:
        return True
    normalised = value.lower()
    if normalised in _COMMON_OSS:
        return True
    if allow_redistribution:
        return True
    # deny known forbidden labels and anything unrecognised
    if normalised in _DENIED_KEYWORDS:
        return False
    # unrecognised – treat as denied
    return False
