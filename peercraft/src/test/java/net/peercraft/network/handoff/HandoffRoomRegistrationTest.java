package net.peercraft.network.handoff;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class HandoffRoomRegistrationTest {
    @Test void lanWithoutRoomCannotSucceed() {
        HandoffRoomRegistration registration = new HandoffRoomRegistration(1, 20);
        assertThrows(IOException.class, registration::await);
    }
    @Test void registrationIsSpecificToOneAttempt() throws Exception {
        HandoffRoomRegistration old = new HandoffRoomRegistration(1, 1000), current = new HandoffRoomRegistration(2, 20);
        old.registered("OLD"); assertEquals("OLD", old.await());
        assertThrows(IOException.class, current::await);
    }
    @Test void failedRegistrationDoesNotAcceptLateReply() {
        HandoffRoomRegistration registration = new HandoffRoomRegistration(1, 1000);
        registration.failed("failed"); registration.registered("LATE");
        assertThrows(IOException.class, registration::await);
    }
}
