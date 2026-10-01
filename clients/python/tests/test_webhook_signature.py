import pytest

from yoonpay import Event, sign_signature, verify_signature


def test_the_shared_vector_verifies(vector):
    assert verify_signature(vector["secret"], vector["header"], vector["body"], vector["timestamp"])
    assert verify_signature(vector["secret"], vector["header"], vector["body"].encode(), vector["timestamp"])
    assert sign_signature(vector["secret"], vector["body"], vector["timestamp"]) == vector["header"]


def test_a_tampered_body_fails(vector):
    assert not verify_signature(vector["secret"], vector["header"], vector["body"] + " ", vector["timestamp"])


def test_a_wrong_secret_fails(vector):
    assert not verify_signature(vector["secret"] + "x", vector["header"], vector["body"], vector["timestamp"])


@pytest.mark.parametrize("offset", [301, -301, 3600])
def test_a_timestamp_more_than_300_seconds_off_fails(vector, offset):
    assert not verify_signature(vector["secret"], vector["header"], vector["body"], vector["timestamp"] + offset)


@pytest.mark.parametrize("offset", [300, -300])
def test_the_tolerance_is_inclusive(vector, offset):
    assert verify_signature(vector["secret"], vector["header"], vector["body"], vector["timestamp"] + offset)


@pytest.mark.parametrize(
    "header",
    [None, "", "nonsense", "t=1790000000", "v1=abc", "t=abc,v1=00", "t=-5,v1=00", "t=١٧٩,v1=00"],
)
def test_malformed_headers_are_rejected(vector, header):
    assert not verify_signature(vector["secret"], header, vector["body"], vector["timestamp"])


def test_an_empty_secret_never_verifies(vector):
    assert not verify_signature("", sign_signature("", vector["body"], vector["timestamp"]), vector["body"], vector["timestamp"])


def test_an_upper_case_signature_is_accepted(vector):
    t, v1 = vector["header"].split(",")
    assert verify_signature(vector["secret"], f"{t},{v1.upper().replace('V1=', 'v1=')}", vector["body"], vector["timestamp"])


def test_event_from_json_exposes_id_type_and_object(vector):
    event = Event.from_json(vector["body"])

    assert event.id == "evt_0199a3f0c2e47a1b9c3d5e6f7a8b9c0d"
    assert event.type == "payment.succeeded"
    assert event.object["status"] == "succeeded"
    assert event.payload["object"] == "event"


def test_an_unknown_event_type_is_kept_as_a_string():
    event = Event.from_json(b'{"id":"evt_1","type":"payment.teleported","data":{"object":{"id":"pay_1"}}}')

    assert event.type == "payment.teleported"


@pytest.mark.parametrize("body", [b"", b"[]", b"not json", b'{"id":"evt_1","type":"x"}', b'{"id":1,"type":"x","data":{"object":{}}}'])
def test_something_else_is_not_an_event(body):
    with pytest.raises(ValueError):
        Event.from_json(body)
