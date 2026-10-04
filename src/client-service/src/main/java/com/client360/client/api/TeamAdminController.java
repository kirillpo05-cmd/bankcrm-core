package com.client360.client.api;

import com.client360.client.persistence.TeamAdminRepository;
import com.client360.client.service.TeamAdminService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team administration (SPEC.md §9.3, RB-US-06).
 *
 * <p>A team is the unit of the {@code TEAM} data scope (RB-BR-02), so every write here decides who
 * can see whose clients. There is no delete: a team with history cannot be removed without
 * orphaning the clients that point at it, and {@code active = false} is what retirement looks like.
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamAdminController {

    private final TeamAdminService teams;

    public TeamAdminController(TeamAdminService teams) {
        this.teams = teams;
    }

    /** {@code user:read}, with member counts. */
    @GetMapping
    public List<TeamResponse> list(CurrentUser caller) {
        return teams.list(caller).stream().map(TeamResponse::of).toList();
    }

    @PostMapping
    public ResponseEntity<TeamResponse> create(@Valid @RequestBody CreateTeamRequest body, CurrentUser caller) {
        TeamAdminRepository.Team created = teams.create(
                body.name(), body.code(), body.timezone(), body.parentTeamId(), body.supervisorId(), caller);
        return ResponseEntity.created(java.net.URI.create("/api/v1/teams/" + created.id()))
                .eTag(ETags.of(created.version()))
                .body(TeamResponse.of(created));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TeamResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateTeamRequest body,
            CurrentUser caller) {
        TeamAdminRepository.Team saved = teams.update(
                id,
                ifMatch,
                body.name(),
                body.supervisorId(),
                Boolean.TRUE.equals(body.clearSupervisor()),
                body.parentTeamId(),
                Boolean.TRUE.equals(body.clearParentTeam()),
                body.timezone(),
                body.active(),
                caller);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(TeamResponse.of(saved));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<Void> addMember(
            @PathVariable UUID id, @Valid @RequestBody AddMemberRequest body, CurrentUser caller) {
        teams.addMember(id, body.userId(), Boolean.TRUE.equals(body.isPrimary()), caller);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId, CurrentUser caller) {
        teams.removeMember(id, userId, caller);
        return ResponseEntity.noContent().build();
    }

    public record CreateTeamRequest(
            @NotBlank @Size(max = 120) String name,

            @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9_-]+", message = "must be alphanumeric")
            String code,

            @Size(max = 64) String timezone,
            UUID parentTeamId,
            UUID supervisorId) {}

    public record UpdateTeamRequest(
            @Size(max = 120) String name,
            UUID supervisorId,
            Boolean clearSupervisor,
            UUID parentTeamId,
            Boolean clearParentTeam,
            @Size(max = 64) String timezone,
            Boolean active) {}

    /** @param isPrimary CP-BR-03 derives a new client's team from the owner's primary membership */
    public record AddMemberRequest(@NotNull UUID userId, Boolean isPrimary) {}

    public record TeamResponse(
            UUID id,
            String name,
            String code,
            UUID supervisorId,
            UUID parentTeamId,
            String timezone,
            boolean active,
            Integer memberCount,
            int version) {

        static TeamResponse of(TeamAdminRepository.Team team) {
            return new TeamResponse(
                    team.id(),
                    team.name(),
                    team.code(),
                    team.supervisorId(),
                    team.parentTeamId(),
                    team.timezone(),
                    team.active(),
                    null,
                    team.version());
        }

        static TeamResponse of(TeamAdminRepository.TeamView team) {
            return new TeamResponse(
                    team.id(),
                    team.name(),
                    team.code(),
                    team.supervisorId(),
                    team.parentTeamId(),
                    team.timezone(),
                    team.active(),
                    team.memberCount(),
                    team.version());
        }
    }
}
