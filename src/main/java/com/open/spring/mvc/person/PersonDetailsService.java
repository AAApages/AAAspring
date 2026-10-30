package com.open.spring.mvc.person;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
This class has an instance of Java Persistence API (JPA)
-- @Autowired annotation. Allows Spring to resolve and inject collaborating beans into our bean.
-- Spring Data JPA will generate a proxy instance
-- Below are some CRUD methods that we can use with our database
*/

@Service
@Transactional
public class PersonDetailsService implements UserDetailsService {  // "implements" ties ModelRepo to Spring Security
    // Encapsulate many object into a single Bean (Person, Roles, and Scrum)
    @Autowired  // Inject PersonJpaRepository
    private PersonJpaRepository personJpaRepository;
    @Autowired  // Inject RoleJpaRepository
    private PersonRoleJpaRepository personRoleJpaRepository;
    @Autowired // Inject PasswordEncoder
    private PasswordEncoder passwordEncoder;

    /*
     * loadUserByUsername Overrides and maps Person & Roles POJO into Spring
     * Security
     */
    @Override
    public UserDetails loadUserByUsername(String uid) throws UsernameNotFoundException {
        Person person = personJpaRepository.findByUid(uid); // setting variable user equal to the method finding the username in the database
        if (person == null) {
		    throw new UsernameNotFoundException("User not found with username: " + uid);
        }
        Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
        person.getRoles().forEach(role -> { //loop through roles
            if (role == null || role.getName() == null || role.getName().isBlank()) {
                return;
            }
            authorities.add(new SimpleGrantedAuthority(normalizeRoleName(role.getName()))); //create a SimpleGrantedAuthority by passed in role, adding it all to the authorities list, list of roles gets past in for spring security
        });
        // train spring security to User and Authorities
        User user = new User(person.getUid(), person.getPassword(), authorities);
        return user;
    }

    private String normalizeRoleName(String roleName) {
        String normalized = roleName.trim();
        return normalized.startsWith("ROLE_") ? normalized : "ROLE_" + normalized;
    }

    /* Person Section */

    public List<Person> listAll() {
        return personJpaRepository.findAllByOrderByNameAsc();
    }

    // custom query to find match to name or uid
    public List<Person> list(String name, String uid) {
        return personJpaRepository.findByNameContainingIgnoreCaseOrUidContainingIgnoreCase(name, uid);
    }

    // custom query to find anything containing term in name or uid ignoring case
    public List<Person> listLike(String term) {
        return personJpaRepository.findByNameContainingIgnoreCaseOrUidContainingIgnoreCase(term, term);
    }

    // custom query to find anything containing term in name or uid ignoring case
    public List<Person> listLikeNative(String term) {
        String like_term = String.format("%%%s%%",term);  // Like required % rappers
        return personJpaRepository.findByLikeTermNative(like_term);
    }

    // Hashes the plaintext in person.password. The plaintext stays in the transient
    // newPassword field so JPA can check it against Person.PASSWORD_RULE on save.
    private void hashNewPassword(Person person) {
        person.setNewPassword(person.getPassword());
        person.setPassword(passwordEncoder.encode(person.getPassword()));
    }

    // Saves and flushes, so JPA validates now and a weak password throws
    // ConstraintViolationException here instead of later, wrapped in a commit error.
    // save() on an existing person is a merge, which skips @Transient fields, so the
    // plaintext is copied onto the managed copy that JPA actually validates.
    private void saveAndValidate(Person person) {
        String newPassword = person.getNewPassword();
        Person managed = personJpaRepository.save(person);
        managed.setNewPassword(newPassword);
        personJpaRepository.flush();
    }

    // encode password prior to save
    public void save(Person person) {
        if (person.getPassword() == null || person.getPassword().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        hashNewPassword(person);
        saveAndValidate(person);
    }

    public void save(Person person, Boolean samePassword) {
        if (person.getPassword() == null) { // this will occur if ADMIN_PASSWORD and DEFAULT_PASSWORD are not set in .env
            throw new IllegalArgumentException("Password cannot be null");
        }
        if (!samePassword) {
            // Only a changing password is plaintext here; when samePassword is true,
            // person.password already holds the stored BCrypt hash.
            hashNewPassword(person);
            // Invalidates every JWT already issued to this person -- see the field
            // comment on Person.tokenVersion.
            person.setTokenVersion((person.getTokenVersion() == null ? 0L : person.getTokenVersion()) + 1);
        }
        saveAndValidate(person); // Save the person to the database
    }

    public Person get(long id) {
        return (personJpaRepository.findById(id).isPresent())
                ? personJpaRepository.findById(id).get()
                : null;
    }

    public Person getByUid(String uid) {
        return (personJpaRepository.findByUid(uid));
    }

    public void delete(long id) {
        personJpaRepository.deleteById(id);
    }

    public void defaults(String password, String roleName) {
        for (Person person : listAll()) {
            if (person.getPassword() == null || person.getPassword().isEmpty() || person.getPassword().isBlank()) {
                person.setPassword(passwordEncoder.encode(password));
            }
            if (person.getRoles().isEmpty()) {
                PersonRole role = personRoleJpaRepository.findByName(roleName);
                if (role != null) { // verify role
                    person.getRoles().add(role);
                }
            }
        }
    }

    public List<PersonRole> listAllRoles() {
        return personRoleJpaRepository.findAll();
    }

    public PersonRole findRole(String roleName) {
        return personRoleJpaRepository.findByName(roleName);
    }

    
    public void addRoleToPerson(String uid, String roleName) { // by passing in the two strings you are giving the user that certain role
        Person person = personJpaRepository.findByUid(uid);
        if (person != null) { // verify person
            PersonRole role = personRoleJpaRepository.findByName(roleName);
            if (role != null) { // verify role
                boolean addRole = true;
                for (PersonRole roleObj : person.getRoles()) { // only add if user is missing role
                    if (roleObj.getName().equals(roleName)) {
                        addRole = false;
                        break;
                    }
                }
                if (addRole)
                    person.getRoles().add(role); // everything is valid for adding role
            }
        }
    }

    /**
     * The role with this name, made the first time it is needed. A role is a row in person_role, not a change to
     * the schema, so a new role needs no database step.
     */
    public PersonRole findOrCreateRole(String roleName) {
        PersonRole role = personRoleJpaRepository.findByName(roleName);
        return role != null ? role : personRoleJpaRepository.save(new PersonRole(roleName));
    }

    /**
     * Gives the person this role, or takes it away, and says whether there is such a person. Giving a role they
     * have, or taking one they do not, changes nothing.
     */
    public boolean setRole(String uid, String roleName, boolean granted) {
        Person person = personJpaRepository.findByUid(uid);
        if (person == null) {
            return false;
        }
        boolean has = person.getRoles().stream().anyMatch(role -> roleName.equals(role.getName()));
        if (granted && !has) {
            person.getRoles().add(findOrCreateRole(roleName));
            personJpaRepository.save(person);
        } else if (!granted && has) {
            person.getRoles().removeIf(role -> roleName.equals(role.getName()));
            personJpaRepository.save(person);
        }
        return true;
    }

    public boolean existsByUid(String uid) {  // check if uid in db
        return personJpaRepository.existsByUid(uid);
    }
}