3. Ticket Booking System
Description Build a backend API for booking event tickets. The system must robustly
handle concurrent booking attempts by implementing a temporary seat reservation
mechanism.
Requirements
● CRUD Operations for Events: Manage event details like name, date, location,
and totalSeats.
● Seat Reservation and Booking Workflow:
○ Hold Seats: An endpoint for a user to request a hold on specific seats. This
does not create a final booking but places a temporary reservation on the
seats for 5 minutes. On success, it returns a unique holdId.
○ Confirm Booking: A separate endpoint that takes the holdId to confirm the
booking. This step validates the hold, creates a permanent booking record,
and makes the seats officially unavailable.
○ Expired Hold Release: The system must include a mechanism to
automatically release seats for any hold that is not confirmed within the
5-minute window, making them available to other users. You must implement
the logic that would handle this cleanup.

● Booking Management:
○ View: View booking details.
○ Cancel: Cancel a confirmed booking.
● Business Rules:
○ Ensure the number of booked and held seats never exceeds an event's
totalSeats.
○ Prevent double booking for the same user and event.
● Availability Endpoint: An endpoint to fetch event details along with the current
number of truly available seats (total minus both confirmed and held seats).
Evaluation Criteria
● Clean API design (RESTful principles, proper status codes).
● Efficient database queries and schema design.
● Error handling and input validation.
● Audit Trail: Implement soft deletes for bookings (e.g., mark as "Canceled").
● Correct handling of business rules and constraints, especially for preventing
overbooking.
● A clear and logical implementation of the hold-then-confirm pattern and the automatic
release of expired holds.
