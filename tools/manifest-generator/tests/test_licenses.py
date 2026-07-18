import pytest

from mcumanifest.licenses import hosted_allowed


def test_empty_and_none_are_allowed():
    assert hosted_allowed(None) is True
    assert hosted_allowed("") is True
    assert hosted_allowed("   ") is True


def test_common_oss_identifiers_are_allowed():
    for identifier in ["mit", "Apache-2.0", "gpl-3.0-only", "LGPL-2.1-only"]:
        assert hosted_allowed(identifier) is True


def test_denied_keywords_blocked():
    denied = ["all rights reserved", "ARR", "custom", "unknown"]
    for val in denied:
        assert hosted_allowed(val) is False


def test_unrecognised_value_blocked_by_default():
    assert hosted_allowed("completely-random-string") is False


def test_allow_redistribution_overrides_block():
    assert hosted_allowed("ARR", allow_redistribution=True) is True
    assert hosted_allowed("unknown", allow_redistribution=True) is True
