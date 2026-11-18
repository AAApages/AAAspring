package com.open.spring.mvc.bathroom;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@Controller
public class TinkleViewController {

    @Autowired
    private TinkleJPARepository tinkleRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private TinkleStatisticsService tinkleStatisticsService;

    @GetMapping("/admin/tinkle-view")
    public String showTinkleView(
        Model model, 
        @AuthenticationPrincipal UserDetails userDetails,
        RedirectAttributes redirectAttributes) {
        // Check if user is authenticated
        if (userDetails == null) {
            return "redirect:/login";
        }

        // Get the logged-in user's email
        String email = userDetails.getUsername();
        Person user = personRepository.findByUid(email);

        // Check if the user exists and has ROLE_ADMIN
        if (user == null || !user.hasRole("ROLE_ADMIN")) {
            return "redirect:/access-denied";
        }

        try {
            // Get the current date and determine the current week's start and end dates
            LocalDate today = LocalDate.now();
            LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate currentWeekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)); // Changed to FRIDAY

            // Create a list of weekday objects for the current week (Monday-Friday only)
            List<WeekdayData> weekdays = new ArrayList<>();
            for (int i = 0; i < 5; i++) { // Changed from 7 to 5 days
                LocalDate date = currentWeekStart.plusDays(i);
                WeekdayData weekday = new WeekdayData();
                weekday.setDate(date.format(DateTimeFormatter.ISO_DATE));
                weekday.setName(date.getDayOfWeek().toString().substring(0, 1) + 
                                date.getDayOfWeek().toString().substring(1).toLowerCase() + 
                                " (" + date.getMonthValue() + "/" + date.getDayOfMonth() + ")");
                weekday.setIsToday(date.equals(today));
                weekday.setEntries(new ArrayList<>()); // Initialize with empty list
                weekdays.add(weekday);
            }

            // Fetch all Tinkle records from the database
            List<Tinkle> tinkleList = tinkleRepository.findAll();
            
            // Add durations and format data for each entry
            List<Map<String, Object>> tinkleDataFormatted = new ArrayList<>();
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            for (Tinkle tinkle : tinkleList) {
                Map<String, Object> row = new HashMap<>();
                
                // Handle null values
                String personName = tinkle.getPersonName() != null ? tinkle.getPersonName() : "Unknown";
                String timeIn = tinkle.getTimeIn() != null ? tinkle.getTimeIn() : "";
                
                // Skip entries with empty timeIn
                if (timeIn.isEmpty()) continue;
                

                row.put("person_name", personName);
                String formattedTimeIn = tinkleStatisticsService.formatTimeIn(timeIn);
                row.put("timeIn", formattedTimeIn);
                
                // Extract day from timeIn
                String day = tinkleStatisticsService.extractDay(timeIn);
                row.put("day", day);
                
                // Calculate and format duration
                String duration = tinkleStatisticsService.calculateDurationFormatted(timeIn);
                row.put("duration", duration);
                
                // Add to formatted list
                tinkleDataFormatted.add(row);
                
                // Determine which weekday this entry belongs to
                try {
                    String[] times = timeIn.split("--");
                    if (times.length >= 1) {
                        LocalDateTime entryDateTime = LocalDateTime.parse(times[0].trim(), dateFormatter);
                        LocalDate entryDate = entryDateTime.toLocalDate();
                        
                        // If the entry is within the current week (Monday-Friday only)
                        if (!entryDate.isBefore(currentWeekStart) && !entryDate.isAfter(currentWeekEnd)) {
                            int dayOfWeek = entryDate.getDayOfWeek().getValue(); // 1 (Monday) to 7 (Sunday)
                            
                            // Only process weekdays (1-5, Monday-Friday)
                            if (dayOfWeek >= 1 && dayOfWeek <= 5) {
                                int dayIndex = dayOfWeek - 1; // Convert to 0-based index
                                weekdays.get(dayIndex).getEntries().add(row);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip entries with invalid dates
                }
            }

            // Group formatted entries by day (for the original view)
            Map<String, List<Map<String, Object>>> dailyTinkleData = 
                tinkleDataFormatted.stream()
                    .filter(row -> row.get("day") != null)
                    .collect(Collectors.groupingBy(row -> (String) row.get("day")));
            
            // Calculate average weekly durations
            Map<String, String> averageWeeklyDurations = tinkleStatisticsService.calculateAverageWeeklyDurationsFormatted(tinkleList);
            Map<String, Long> rawWeeklyDurations = tinkleStatisticsService.calculateAverageWeeklyDurations(tinkleList);
            
            // Add everything to the model
            model.addAttribute("dailyTinkleData", dailyTinkleData);
            model.addAttribute("averageWeeklyDurations", averageWeeklyDurations);
            model.addAttribute("chartLabels", rawWeeklyDurations.keySet());
            model.addAttribute("chartData", rawWeeklyDurations.values());
            
            // Add weekday data for the tabs
            model.addAttribute("weekdays", weekdays);
            model.addAttribute("currentWeekStart", currentWeekStart.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")));
            model.addAttribute("currentWeekEnd", currentWeekEnd.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")));
            
            return "tinkle-view";
            
        } catch (Exception e) {
            e.printStackTrace();
            redirectAttributes.addFlashAttribute("error", "An error occurred while loading tinkle data: " + e.getMessage());
            return "error";
        }
    }
    
    // Inner class to hold weekday data
    public static class WeekdayData {
        private String date;
        private String name;
        private boolean isToday;
        private List<Map<String, Object>> entries;
        
        public String getDate() {
            return date;
        }
        
        public void setDate(String date) {
            this.date = date;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public boolean getIsToday() {
            return isToday;
        }
        
        public void setIsToday(boolean isToday) {
            this.isToday = isToday;
        }
        
        public List<Map<String, Object>> getEntries() {
            return entries;
        }
        
        public void setEntries(List<Map<String, Object>> entries) {
            this.entries = entries;
        }
    }
}