from __future__ import annotations

import gzip
from datetime import datetime

import pytest

from gussetline.collect.archive import Archive, Sighting, digest_of
from gussetline.errors import ValidationError

SEEN = datetime(2025, 5, 12, 9, 0)
LATER = datetime(2025, 5, 12, 15, 30)


@pytest.fixture
def archive(tmp_path):
    return Archive(tmp_path / "raw")


def test_a_digest_is_stable_and_depends_on_the_bytes():
    assert digest_of(b"one") == digest_of(b"one")
    assert digest_of(b"one") != digest_of(b"two")
    assert len(digest_of(b"one")) == 32


def test_storing_a_payload_writes_it_and_marks_it_fresh(archive):
    sighting = archive.put(b'{"fares":[]}', "gds", "/search", SEEN)
    assert sighting.fresh
    assert sighting.size == 12
    assert archive.holds(sighting.digest)
    assert archive.get(sighting.digest) == b'{"fares":[]}'


def test_the_same_payload_again_is_not_written_twice(archive):
    first = archive.put(b"same", "gds", "/search", SEEN)
    second = archive.put(b"same", "gds", "/search", LATER)
    assert first.digest == second.digest
    assert first.fresh
    assert not second.fresh
    assert archive.stored_count() == 1


def test_both_sightings_are_recorded_even_when_the_payload_repeats(archive):
    archive.put(b"same", "gds", "/search", SEEN)
    archive.put(b"same", "gds", "/search", LATER)
    rows = archive.manifest()
    assert len(rows) == 2
    assert [r["fresh"] for r in rows] == [True, False]


def test_a_different_payload_gets_its_own_blob(archive):
    archive.put(b"one", "gds", "/search", SEEN)
    archive.put(b"two", "gds", "/search", LATER)
    assert archive.stored_count() == 2


def test_the_payload_is_stored_compressed(tmp_path):
    archive = Archive(tmp_path / "raw")
    payload = b"x" * 5000
    sighting = archive.put(payload, "gds", "/search", SEEN)
    on_disk = archive._path_for(sighting.digest).read_bytes()
    assert len(on_disk) < len(payload)
    assert gzip.decompress(on_disk) == payload


def test_reading_a_digest_we_do_not_hold_is_refused(archive):
    with pytest.raises(ValidationError):
        archive.get("0" * 32)


def test_a_digest_that_is_not_one_is_refused(archive):
    with pytest.raises(ValidationError):
        archive.holds("ab")


def test_the_manifest_of_a_fresh_archive_is_empty(archive):
    assert archive.manifest() == []


def test_the_context_rides_along_on_the_sighting(archive):
    archive.put(
        b"body", "gds", "/search", SEEN, context={"market": "FRA-JFK", "departure": "2025-06-01"}
    )
    assert archive.manifest()[0]["context"]["market"] == "FRA-JFK"


def test_a_non_two_hundred_response_is_archived_too(archive):
    sighting = archive.put(b"gateway timeout", "gds", "/search", SEEN, status=504)
    assert sighting.status == 504
    assert archive.manifest()[0]["status"] == 504


def test_the_sightings_of_one_payload_can_be_listed(archive):
    archive.put(b"same", "gds", "/search", SEEN)
    archive.put(b"other", "gds", "/search", SEEN)
    archive.put(b"same", "gds", "/search", LATER)
    digest = digest_of(b"same")
    assert len(archive.sightings_of(digest)) == 2


def test_replay_gives_every_distinct_payload_once(archive):
    archive.put(b"one", "gds", "/a", SEEN)
    archive.put(b"one", "gds", "/a", LATER)
    archive.put(b"two", "ndc", "/b", SEEN)
    seen = [payload for _, payload in archive.replay()]
    assert sorted(seen) == [b"one", b"two"]


def test_replay_can_be_narrowed_to_one_supplier(archive):
    archive.put(b"one", "gds", "/a", SEEN)
    archive.put(b"two", "ndc", "/b", SEEN)
    assert [p for _, p in archive.replay("ndc")] == [b"two"]


def test_replay_hands_back_the_manifest_row_with_the_payload(archive):
    archive.put(b"one", "gds", "/a", SEEN, context={"market": "FRA-JFK"})
    row, payload = next(iter(archive.replay()))
    assert row["context"]["market"] == "FRA-JFK"
    assert payload == b"one"


def test_an_archive_reopened_on_the_same_directory_keeps_everything(tmp_path):
    first = Archive(tmp_path / "raw")
    sighting = first.put(b"kept", "gds", "/a", SEEN)
    second = Archive(tmp_path / "raw")
    assert second.holds(sighting.digest)
    assert second.get(sighting.digest) == b"kept"
    assert len(second.manifest()) == 1


def test_a_sighting_reads_back_in_one_line():
    sighting = Sighting(digest="abcdef0123", supplier="gds", url="/a", seen_at=SEEN)
    assert str(sighting) == "2025-05-12 09:00 gds new abcdef01"
    stale = Sighting(digest="abcdef0123", supplier="gds", url="/a", seen_at=SEEN, fresh=False)
    assert "same" in str(stale)


def test_an_archive_reads_back_with_its_size(archive):
    archive.put(b"one", "gds", "/a", SEEN)
    assert str(archive).startswith("1 payloads under ")
