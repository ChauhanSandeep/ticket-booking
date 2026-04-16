package com.ticketbooking.service;

import com.ticketbooking.dto.response.EventAvailabilityResponse;
import com.ticketbooking.dto.response.SeatDetail;
import com.ticketbooking.entity.Event;
import com.ticketbooking.entity.Seat;
import com.ticketbooking.entity.enums.SeatStatus;
import com.ticketbooking.exception.ResourceNotFoundException;
import com.ticketbooking.repository.EventRepository;
import com.ticketbooking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SeatAvailabilityService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    @Transactional(readOnly = true)
    public EventAvailabilityResponse getAvailability(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));

        List<Seat> seats = seatRepository.findByEventId(eventId);

        long availableCount = seats.stream().filter(s -> s.getStatus() == SeatStatus.AVAILABLE).count();
        long heldCount = seats.stream().filter(s -> s.getStatus() == SeatStatus.HELD).count();
        long bookedCount = seats.stream().filter(s -> s.getStatus() == SeatStatus.BOOKED).count();

        List<SeatDetail> seatDetails = seats.stream()
                .sorted(Comparator.comparingInt(s -> Integer.parseInt(s.getSeatNumber())))
                .map(s -> SeatDetail.builder()
                        .seatNumber(s.getSeatNumber())
                        .status(s.getStatus())
                        .build())
                .toList();

        return EventAvailabilityResponse.builder()
                .eventId(event.getId())
                .eventName(event.getName())
                .eventDate(event.getEventDate())
                .location(event.getLocation())
                .totalSeats(event.getTotalSeats())
                .availableCount(availableCount)
                .heldCount(heldCount)
                .bookedCount(bookedCount)
                .seats(seatDetails)
                .build();
    }
}
