package dev.lm15.types;

/** The fate of one batch request, in submission order. */
public record BatchEntry(int index, BatchOutcome outcome, Response response, ErrorDetail error) {
    public BatchEntry {
        if (index < 0) throw ValidationException.value("BatchEntry.index must be a non-negative int");
        Check.required(outcome, "batch outcome");
        switch (outcome) {
            case SUCCEEDED -> { if (response == null || error != null) throw ValidationException.value("succeeded entries carry a Response and no error"); }
            case ERRORED -> { if (error == null || response != null) throw ValidationException.value("errored entries carry an ErrorDetail and no response"); }
            default -> { if (response != null || error != null) throw ValidationException.value(outcome.wire() + " entries carry neither response nor error"); }
        }
    }

    public boolean ok() { return outcome == BatchOutcome.SUCCEEDED; }
}
