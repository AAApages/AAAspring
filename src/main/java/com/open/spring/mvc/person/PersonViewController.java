package com.open.spring.mvc.person;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.open.spring.mvc.person.Email.Email;
import com.open.spring.mvc.person.Email.ResetCode;
import com.open.spring.mvc.person.Email.VerificationCode;
import com.open.spring.mvc.person.HttpRequest.HttpSender;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.GrantedAuthority;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Built using article: https://docs.spring.io/spring-framework/docs/3.2.x/spring-framework-reference/html/mvc.html
// or similar: https://asbnotebook.com/2020/04/11/spring-boot-thymeleaf-form-validation-example/
@Controller
@RequestMapping("/mvc/person")
public class PersonViewController {
    private static final Logger logger = LoggerFactory.getLogger(PersonViewController.class);

    // Autowired enables Control to connect HTML and POJO Object to database easily for CRUD
    @Autowired
    private PersonDetailsService repository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SessionRegistry sessionRegistry;

    // application.properties default, overridden by DEFAULT_PASSWORD in .env
    @Value("${person.default-password}")
    private String defaultPassword;

    //@Autowired
    //private PersonJpaRepository find;

///////////////////////////////////////////////////////////////////////////////////////////
/// "Read" Get and Post mappings

    @GetMapping("/read")
    public String person(Authentication authentication, Model model) {
        //check user authority
        UserDetails userDetails = (UserDetails)authentication.getPrincipal(); 
        boolean isAdmin = false;
        for (GrantedAuthority authority : userDetails.getAuthorities()) {
            if(String.valueOf("ROLE_ADMIN").equals(authority.getAuthority())){
                isAdmin = true;
                break;
            }
        }
        if (isAdmin == true){
            List<Person> list = repository.listAll();  // Fetch all persons
            model.addAttribute("list", list);  // Add the list to the model for the view
        }
        else {
            Person person = repository.getByUid(userDetails.getUsername());  // Fetch the person by email
            List<Person> list = Collections.singletonList(person);  // Create a single element list
            model.addAttribute("list", list);  // Add the list to the model for the view 
        }
        return "person/read";  // Return the template for displaying persons
    }

    @GetMapping("/read/{id}")
    public String person(Authentication authentication, @PathVariable("id") int id, Model model) {
        //check user authority
        UserDetails userDetails = (UserDetails)authentication.getPrincipal(); 
        boolean isAdmin = false;
        for (GrantedAuthority authority : userDetails.getAuthorities()) {
            if(String.valueOf("ROLE_ADMIN").equals(authority.getAuthority())){
                isAdmin = true;
                break;
            }
        }
        if (isAdmin == true){
            Person person = repository.get(id);  // Fetch the person by ID
            List<Person> list = Arrays.asList(person);  // Convert the single person into a list for consistency
            model.addAttribute("list", list);  // Add the list to the model for the view 
        }
        else if(repository.getByUid(userDetails.getUsername()).getId() == id){
            Person person = repository.getByUid(userDetails.getUsername());  // Fetch the person by email
            List<Person> list = Collections.singletonList(person);  // Create a single element list
            model.addAttribute("list", list);  // Add the list to the model for the view 
        }
        return "person/read";  // Return the template for displaying the person
    }


    /* Gathers the attributes filled out in the form, tests for and retrieves validation error
    @param - Person object with @Valid
    @param - BindingResult object
     */
    @PostMapping("/create")
    public String personSave(@Valid Person person, BindingResult bindingResult) {
        // Validation of Decorated PersonForm attributes
        if (bindingResult.hasErrors()) {
            return "person/create";
        }
        repository.save(person);

        if (!(person.hasRole("ROLE_ADMIN") || person.hasRole("ROLE_TEACHER"))) {
            repository.addRoleToPerson(person.getUid(), "ROLE_STUDENT");
        }
        
        // Redirect to next step
        return "redirect:/mvc/person/read";
    }

    /*  The HTML template Forms and PersonForm attributes are bound
        @return - template for person form
        @param - Person Class
    */
    @GetMapping("/create")
    public String personAdd(Person person) {
        return "person/create";
    }



    @PostMapping("/update")
    public String personUpdateSave(
        Authentication authentication,
        @Valid Person person,
        BindingResult bindingResult,
        @RequestParam(value = "currentPassword", required = false) String currentPassword
    ) {
        // Check if the user has admin authority
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        boolean isAdmin = userDetails.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        Person actor = repository.getByUid(userDetails.getUsername());
        Person personToUpdate = repository.getByUid(person.getUid());

        if (personToUpdate == null || actor == null) {
            logger.warn("AUDIT profile_update_failed actor={} target={} reason=not_found", userDetails.getUsername(), person.getUid());
            return "redirect:/e#Unauthorized";
        }

        // If the user is not an admin, they can only update their own details
        if (!isAdmin && !personToUpdate.getId().equals(actor.getId())) {
            logger.warn("AUDIT profile_update_blocked actor={} target={} reason=non_admin_cross_update", actor.getUid(), personToUpdate.getUid());
            return "redirect:/e#Unauthorized";  // Redirect if user tries to update another person's details
        }

        boolean emailChanged = person.getEmail() != null && !person.getEmail().isBlank() && !person.getEmail().equals(personToUpdate.getEmail());
        boolean passwordChanged = person.getPassword() != null && !person.getPassword().isBlank();
        if (!isAdmin && (emailChanged || passwordChanged)) {
            if (currentPassword == null || currentPassword.isBlank() || !passwordEncoder.matches(currentPassword, actor.getPassword())) {
                logger.warn("AUDIT profile_update_blocked actor={} target={} reason=invalid_current_password", actor.getUid(), personToUpdate.getUid());
                return "redirect:/mvc/person/update/user?error=invalid-current-password";
            }
        }

        boolean samePassword = true;
        StringBuilder changedFields = new StringBuilder();

        // Update fields if the new values are provided
        if (passwordChanged) {
            personToUpdate.setPassword(person.getPassword());
            samePassword = false;
            changedFields.append("password,");
        }
        if (person.getName() != null && !person.getName().isBlank() && !person.getName().equals(personToUpdate.getName())) {
            personToUpdate.setName(person.getName());
            changedFields.append("name,");
        }
        if (emailChanged) {
            personToUpdate.setEmail(person.getEmail());
            changedFields.append("email,");
        }
        if (person.getKasmServerNeeded() != null && !person.getKasmServerNeeded().equals(personToUpdate.getKasmServerNeeded())) {
            personToUpdate.setKasmServerNeeded(person.getKasmServerNeeded());
            changedFields.append("kasmServerNeeded,");
        }
        if (person.getSid() != null && !person.getSid().equals(personToUpdate.getSid())) {
            personToUpdate.setSid(person.getSid());
            changedFields.append("sid,");
        }
                

        // Save the updated person and ensure the roles are correctly maintained
        repository.save(personToUpdate, samePassword);
        repository.addRoleToPerson(person.getUid(), "ROLE_USER");
        repository.addRoleToPerson(person.getUid(), "ROLE_STUDENT");

        String changed = changedFields.length() == 0
            ? "none"
            : changedFields.substring(0, changedFields.length() - 1);
        logger.info("AUDIT profile_update actor={} target={} fields={} admin={}", actor.getUid(), personToUpdate.getUid(), changed, isAdmin);

        return "redirect:/mvc/person/read";  // Redirect to the read page after updating
    }

    @Getter
    public static class PersonRoleDto {
        private String uid;
        PersonRoleDto(String uid){
            this.uid = uid;
        }
    }

    /**
     * Updates a specific role for a person via a RESTful request.
     *
     * @param roleDto the DTO containing the GitHub ID and role name
     * @return String indicating success or failure
     */
    @PostMapping("/update/role")
    public String personRoleUpdateSave(Authentication authentication, @Valid PersonRoleDto roleDto,@RequestParam("roleName") String roleName) {

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        boolean isAdmin = userDetails.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!isAdmin) {
            logger.warn("AUDIT role_update_blocked actor={} target={} role={} reason=non_admin", userDetails.getUsername(), roleDto.getUid(), roleName);
            return "redirect:/e#Unauthorized";
        }

        Person personToUpdate = repository.getByUid(roleDto.getUid());
        if (personToUpdate == null) {
            logger.warn("AUDIT role_update_failed actor={} target={} role={} reason=target_not_found", userDetails.getUsername(), roleDto.getUid(), roleName);
            return "person/update-roles";  // Return error if person not found
        }

        System.out.println(roleName);
        repository.addRoleToPerson(roleDto.getUid(), roleName);  // Add the role to the person
        logger.info("AUDIT role_update actor={} target={} role={}", userDetails.getUsername(), roleDto.getUid(), roleName);

        return "redirect:/mvc/person/read"; // Redirect to the read page after updating
    }

    @Getter
    public static class PersonRolesDto {
        private String uid;
        private List<String> roleNames;
    }

    /**
     * Updates multiple roles for a person via a RESTful request.
     *
     * @param rolesDto the DTO containing the GitHub ID and a list of role names
     * @return ResponseEntity indicating success or failure
     */
    @PostMapping("/update/roles")
    public ResponseEntity<Object> personRolesUpdateSave(Authentication authentication, @RequestBody PersonRolesDto rolesDto) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        boolean isAdmin = userDetails.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!isAdmin) {
            logger.warn("AUDIT role_bulk_update_blocked actor={} target={} reason=non_admin", userDetails.getUsername(), rolesDto.getUid());
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        Person personToUpdate = repository.getByUid(rolesDto.getUid());
        if (personToUpdate == null) {
            logger.warn("AUDIT role_bulk_update_failed actor={} target={} reason=target_not_found", userDetails.getUsername(), rolesDto.getUid());
            return new ResponseEntity<>(personToUpdate, HttpStatus.CONFLICT);  // Return error if person not found
        }

        // Add all roles to the person
        for (String roleName : rolesDto.getRoleNames()) { //I will assume that the roleNames is made of
            repository.addRoleToPerson(rolesDto.getUid(), roleName);
        }

        logger.info("AUDIT role_bulk_update actor={} target={} roles={}", userDetails.getUsername(), rolesDto.getUid(), rolesDto.getRoleNames());

        return new ResponseEntity<>(personToUpdate, HttpStatus.OK);  // Return success response
    }

    @GetMapping("/update/{id}")
    public String personUpdate(@PathVariable("id") int id, Model model) {
        model.addAttribute("person", repository.get(id));
        return "person/update";
    }
    
    @GetMapping("/update/roles/{id}")
    public String personUpdateRoles(@PathVariable("id") int id, Model model) {
        PersonRoleDto roleDto = new PersonRoleDto(repository.get(id).getUid());
        model.addAttribute("roleDto", roleDto);
        return "person/update-roles";
    }

    @GetMapping("/update/user")
    public String personUpdate(Authentication authentication, Model model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        model.addAttribute("person", repository.getByUid(userDetails.getUsername()));  // Add the person to the model
        return "person/update";  // Return the template for the update form
    }

    @GetMapping("/update/roles/user")
    public String personUpdateRoles(Authentication authentication, Model model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        PersonRoleDto roleDto = new PersonRoleDto(userDetails.getUsername());
        model.addAttribute("roleDto", roleDto);
        return "person/update-roles";
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Person-Quiz" Get mappings

    @GetMapping("/person-quiz")
    public String personQuiz(Model model){
        List<Person> list = repository.listAll();  // Fetch all persons
        model.addAttribute("person", list.get((int)(Math.random()*list.size())));  // Add the list to the model for the view
        return "person/person-quiz";
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Delete" Get mappings
    private int numAdmins(){
        int numAdmins = 0;
        List<Person> personList = repository.listAll();
        for(int i=0; i<personList.size();i++){
            if(personList.get(i).hasRole("ROLE_ADMIN")){
                numAdmins++;
            }
        }
        return numAdmins;
    }


    @GetMapping("/delete/user")
    public String personDelete(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
         //don't delete an admin account when only 1 exists
        Person personToDelete = repository.getByUid(userDetails.getUsername());
        if(personToDelete.hasRole("ROLE_ADMIN") && numAdmins()<2){
            return "redirect:/error/401";
        }
        
        repository.delete(personToDelete.getId());  // Delete the person by ID
        return "redirect:/logout";  // logout the user
    }

    /**sq
     * Deletes a person by ID.
     *
     * @param id the ID of the person to delete
     * @return redirect to the read page after deletion
     */
    @GetMapping("/delete/{id}")
    public String personDelete(Authentication authentication, @PathVariable("id") long id) { 
        //don't delete an admin account when only 1 exists
        Person personToDelete = repository.get(id);
         if(personToDelete.hasRole("ROLE_ADMIN") && numAdmins()<2){
            return "redirect:/error/401";
        }

        //don't redirect to read page if you delete yourself
        //check before deleting from database to avoid imploding the backend
        boolean deletingYourself = false;
        if (repository.getByUid(((UserDetails)authentication.getPrincipal()).getUsername()).getId() == id){
            deletingYourself = true;
        }
        repository.delete(id);  // Delete the person by ID
        if(deletingYourself){
            return "redirect:/logout"; //logout the user
        }
        
        return "redirect:/mvc/person/read";  // Redirect to the read page after deletion
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Reset" Post and Get mappings

    @Getter
    public static class PersonPasswordReset {
        private String uid;
    }

    @PostMapping("/reset/start")
    public ResponseEntity<Object> resetPassword(@RequestBody PersonPasswordReset personPasswordReset){
        if (personPasswordReset == null || personPasswordReset.getUid() == null || personPasswordReset.getUid().isBlank()) {
            return new ResponseEntity<Object>(HttpStatus.BAD_REQUEST);
        }

        Person personToReset = repository.getByUid(personPasswordReset.getUid());
        
        //person not found
        if (personToReset == null){
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        //don't allow people to reset the passwords of admins
        if (personToReset.hasRole("ROLE_ADMIN")){
            return new ResponseEntity<Object>(HttpStatus.UNAUTHORIZED);
        }

        //dont allow people to send emails/ reset password of default users (such as toby)
        Person[] databasePersons = Person.init(defaultPassword);
        for (Person person : databasePersons) {
            if(person.getUid().equals(personToReset.getUid())){
                return new ResponseEntity<Object>(HttpStatus.UNAUTHORIZED);
            }
        }

        // enforce active-token and rolling-window rate limits
        if(!ResetCode.canIssueResetCode(personToReset.getUid())){
            return new ResponseEntity<Object>(HttpStatus.TOO_MANY_REQUESTS);
        }

        //finally send a password reset email to the person
        String resetToken = ResetCode.GenerateResetCode(personToReset.getUid());
        if (resetToken == null) {
            return new ResponseEntity<Object>(HttpStatus.TOO_MANY_REQUESTS);
        }

        try {
            Email.sendPasswordResetEmail(personToReset.getEmail(), resetToken);
        } catch (Exception ex) {
            ResetCode.removeCodeByUid(personToReset.getUid());
            logger.warn("AUDIT reset_email_send_failed uid={} reason={}", personToReset.getUid(), ex.getMessage());
        }
        return new ResponseEntity<Object>(HttpStatus.OK);
    }

    @Getter
    public static class PersonPasswordResetCode {
        private String uid;
        private String code;
    }

    @PostMapping("/reset/check")
    public ResponseEntity<Object> resetPasswordCheck(@RequestBody PersonPasswordResetCode personPasswordResetCode){
        if (personPasswordResetCode == null || personPasswordResetCode.getUid() == null || personPasswordResetCode.getUid().isBlank()) {
            return new ResponseEntity<Object>(HttpStatus.BAD_REQUEST);
        }

        Person personToReset = repository.getByUid(personPasswordResetCode.getUid());

        //person not found
        if (personToReset == null){
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        // code to check doesn't exist
        if(personPasswordResetCode.getCode() == null){
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        // single-use + expiration + signature validation handled in ResetCode
        if(ResetCode.validateAndConsume(personToReset.getUid(), personPasswordResetCode.getCode())){
            personToReset.setPassword(defaultPassword);
            repository.save(personToReset, false);

            return new ResponseEntity<Object>(HttpStatus.OK);
        }
        return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
    }


    @GetMapping("/reset")
    public String reset() {
        return "person/reset";
    }

    @GetMapping("/reset/check")
    public String resetCheck() {
        return "person/resetCheck";
    }

    @PostMapping("/reset/admin/{id}")
    public ResponseEntity<Object> adminResetPassword(@PathVariable Long id, Authentication authentication) {
        // Only admins can use this endpoint
        boolean isAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        Person personToReset = repository.get(id);
        if (personToReset == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        personToReset.setPassword(defaultPassword);
        repository.save(personToReset, false);

        logger.warn("AUDIT admin_password_reset admin={} target_uid={}", authentication.getName(), personToReset.getUid());
        return new ResponseEntity<>(HttpStatus.OK);
    }

    // Matches student emails shaped like "firstnamelastinitial12345@stu.powayusd.com" and
    // captures the trailing 5 digits, which must match the last 5 digits of the account's sid.
    private static final Pattern SCHOOL_EMAIL_DIGITS_PATTERN =
        Pattern.compile("^[a-z]+([0-9]{5})@stu\\.powayusd\\.com$");

    private ResponseEntity<Object> oauthResetDenied(HttpStatus status) {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"verified\":false}";
        return new ResponseEntity<Object>(body, responseHeaders, status);
    }

    @Getter
    public static class PersonOAuthResetVerifyBody {
        private String uid;
        private String idToken;
    }

    // Step 1 of the OAuth-verified reset: caller proves ownership of the account by signing
    // in with their school Google account. The last 5 digits of that (server-verified) email
    // must match the last 5 digits of the sid already on file for this uid before a reset
    // token is issued. Denial responses are intentionally identical regardless of which check
    // failed, so a caller can't use this endpoint to enumerate uid/sid pairs; the specific
    // reason is only ever written to the server log.
    @PostMapping("/reset/oauth/verify")
    public ResponseEntity<Object> resetPasswordOAuthVerify(@RequestBody PersonOAuthResetVerifyBody requestBody) {
        if (requestBody == null || requestBody.getUid() == null || requestBody.getUid().isBlank()) {
            return new ResponseEntity<Object>(HttpStatus.BAD_REQUEST);
        }

        Person personToReset = repository.getByUid(requestBody.getUid());

        //person not found -- same {"verified":false}/403 shape as every other denial below,
        //so an unknown uid can't be distinguished from a real one that failed verification
        if (personToReset == null) {
            logger.warn("AUDIT oauth_reset_denied uid={} reason=not_found", requestBody.getUid());
            return oauthResetDenied(HttpStatus.FORBIDDEN);
        }

        //don't allow people to reset the passwords of admins
        if (personToReset.hasRole("ROLE_ADMIN")) {
            logger.warn("AUDIT oauth_reset_denied uid={} reason=admin_account", personToReset.getUid());
            return oauthResetDenied(HttpStatus.FORBIDDEN);
        }

        //dont allow people to reset password of default users (such as toby)
        Person[] databasePersons = Person.init(defaultPassword);
        for (Person person : databasePersons) {
            if (person.getUid().equals(personToReset.getUid())) {
                logger.warn("AUDIT oauth_reset_denied uid={} reason=default_account", personToReset.getUid());
                return oauthResetDenied(HttpStatus.FORBIDDEN);
            }
        }

        // enforce active-token and rolling-window rate limits, same as the email-based flow
        if (!ResetCode.canIssueResetCode(personToReset.getUid())) {
            return new ResponseEntity<Object>(HttpStatus.TOO_MANY_REQUESTS);
        }

        String verifiedEmail = GoogleIdTokenVerifier.verifyAndGetEmail(requestBody.getIdToken());
        if (verifiedEmail == null) {
            logger.warn("AUDIT oauth_reset_denied uid={} reason=invalid_token", personToReset.getUid());
            return oauthResetDenied(HttpStatus.FORBIDDEN);
        }

        Matcher matcher = SCHOOL_EMAIL_DIGITS_PATTERN.matcher(verifiedEmail.toLowerCase());
        if (!matcher.matches()) {
            logger.warn("AUDIT oauth_reset_denied uid={} reason=email_format", personToReset.getUid());
            return oauthResetDenied(HttpStatus.FORBIDDEN);
        }

        String emailDigits = matcher.group(1);
        String sid = personToReset.getSid();
        if (sid == null || sid.length() < 5) {
            logger.warn("AUDIT oauth_reset_denied uid={} reason=no_sid", personToReset.getUid());
            return oauthResetDenied(HttpStatus.FORBIDDEN);
        }

        String sidDigits = sid.substring(sid.length() - 5);
        if (!emailDigits.equals(sidDigits)) {
            logger.warn("AUDIT oauth_reset_denied uid={} reason=sid_mismatch", personToReset.getUid());
            return oauthResetDenied(HttpStatus.FORBIDDEN);
        }

        String resetToken = ResetCode.GenerateResetCode(personToReset.getUid());
        if (resetToken == null) {
            return oauthResetDenied(HttpStatus.TOO_MANY_REQUESTS);
        }

        logger.info("AUDIT oauth_reset_verified uid={}", personToReset.getUid());

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"verified\":true,\"resetToken\":\"" + resetToken + "\"}";
        return new ResponseEntity<Object>(body, responseHeaders, HttpStatus.OK);
    }

    @Getter
    public static class PersonOAuthResetCompleteBody {
        private String uid;
        private String resetToken;
        private String newPassword;
    }

    // Step 2: spends the single-use token issued by /reset/oauth/verify to actually set the
    // new password. The token, not the client's earlier "verified" claim, is what's trusted here.
    @PostMapping("/reset/oauth/complete")
    public ResponseEntity<Object> resetPasswordOAuthComplete(@RequestBody PersonOAuthResetCompleteBody requestBody) {
        if (requestBody == null || requestBody.getUid() == null || requestBody.getUid().isBlank()) {
            return new ResponseEntity<Object>(HttpStatus.BAD_REQUEST);
        }

        Person personToReset = repository.getByUid(requestBody.getUid());
        if (personToReset == null) {
            return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
        }

        if (!ResetCode.validateAndConsume(personToReset.getUid(), requestBody.getResetToken())) {
            logger.warn("AUDIT oauth_reset_complete_denied uid={} reason=invalid_token", personToReset.getUid());
            return new ResponseEntity<Object>(HttpStatus.FORBIDDEN);
        }

        personToReset.setPassword(requestBody.getNewPassword());
        repository.save(personToReset, false);

        logger.info("AUDIT oauth_reset_completed uid={}", personToReset.getUid());

        // Flask's copy of this password is synced by the frontend calling Flask's own
        // /api/reset-password directly with this same resetToken -- not by Spring pushing
        // it there (see docs/forgot-password-pipeline.md, "Architecture: no backend-to-backend
        // sync"). Flask verifies the token's HMAC locally (shared RESET_TOKEN_SECRET, no
        // network call back to Spring) and owns that write.

        // Force-logout: kill any MVC HttpSession this uid currently holds (e.g. an admin
        // portal tab logged in as this account elsewhere), closing the gap where password
        // resets invalidated JWTs (tokenVersion) but not this session-based auth path. The
        // requesting browser itself gets an explicit /logout call from the frontend on
        // success (see support.md) since that's the only way to also clear its cookies.
        invalidateActiveSessions(personToReset.getUid());

        return new ResponseEntity<Object>(HttpStatus.OK);
    }

    // Marks every SessionRegistry-tracked HttpSession for this uid as expired. Expiry isn't
    // instant server-side destruction -- ConcurrentSessionFilter enforces it the next time
    // that session is used, which is sufficient: the account is unusable via the old session
    // from that point on, matching what a real logout accomplishes.
    private void invalidateActiveSessions(String uid) {
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            String principalUid = (principal instanceof UserDetails userDetails)
                ? userDetails.getUsername()
                : String.valueOf(principal);
            if (!uid.equals(principalUid)) {
                continue;
            }
            for (SessionInformation sessionInformation : sessionRegistry.getAllSessions(principal, false)) {
                sessionInformation.expireNow();
            }
        }
    }

///////////////////////////////////////////////////////////////////////////////////////////
/// "Cookie-Clicker" Post and Get mappings
/// 
    @GetMapping("/cookie-clicker")
    public String cookieClicker(Authentication authentication, Model model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        Person person = repository.getByUid(userDetails.getUsername());  // Fetch the person by email
        List<Person> list = Collections.singletonList(person);  // Create a single element list
        model.addAttribute("list", list);  // Add the list to the model for the view 
        return "person/cookie-clicker";  // Return the template for the update form
    }
    
///////////////////////////////////////////////////////////////////////////////////////////
/// "Verification" Post and Get mappings
/// 

    @Getter
    public static class PersonVerificationBody {
        private String uid;
        private String code;
    }

    @PostMapping("/verification")
    public ResponseEntity<Object> verficiation(@RequestBody PersonVerificationBody personVerificationBody) {
        if(personVerificationBody.getUid() == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
        }

        if(personVerificationBody.getUid().contains("@")){
            //assuming uid is an email
            String code = VerificationCode.GenerateVerificationCode(personVerificationBody.getUid());
            Email.sendVerificationEmail(personVerificationBody.getUid(),code);

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":2}"; //2 == email
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.OK);
        }
        else{
            if(HttpSender.verifyGithub(personVerificationBody.getUid())==true){
                HttpHeaders responseHeaders = new HttpHeaders();
                responseHeaders.setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"state\":1}"; //1 == success
                return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.OK);
            };
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.NOT_FOUND);
        }
    }

    @PostMapping("/verification/code")
    public ResponseEntity<Object> verficiationWithCode(@RequestBody PersonVerificationBody personVerificationBody) {

        //person not found
        if (personVerificationBody.getUid() == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
        }

        // code to check doesn't exist
        if(personVerificationBody.getCode() == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
        }

        if(VerificationCode.getCodeForUid(personVerificationBody.getUid()) == null){
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":0}"; //0 == failed
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.NO_CONTENT);
        }

        //if there is a code submitted for the given uid, and it matches the code that is expected, then reset the users password
        if(VerificationCode.getCodeForUid(personVerificationBody.getUid()).equals(personVerificationBody.getCode())){
            VerificationCode.removeCodeByUid(personVerificationBody.getUid());

            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"state\":1}"; //1 == success
            return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.OK);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"state\":0}"; //0 == failed
        return new ResponseEntity<Object>(body,responseHeaders,HttpStatus.BAD_REQUEST);
    }

}
