package com.odo.team.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.odo.account.application.AccountReader;
import com.odo.team.application.dto.MyTeamResult;
import com.odo.team.application.dto.TeamDetailResult;
import com.odo.team.application.dto.TeamMemberResult;
import com.odo.team.domain.entity.Team;
import com.odo.team.domain.entity.TeamMember;
import com.odo.team.domain.entity.TeamRole;
import com.odo.team.domain.exception.TeamNotFoundException;
import com.odo.team.domain.repository.TeamMemberRepository;
import com.odo.team.domain.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀 스페이스 만들기, 내 팀 목록, 팀 상세, 팀원 목록, 기수 변경, 초대 코드.
 * 팀 안의 동작은 모두 TeamAccess로 팀원인지, 팀장인지 먼저 확인한다.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

	/** 팀장이 맨 앞이고, 나머지는 들어온 순서. */
	private static final Comparator<TeamMember> MEMBER_ORDER = Comparator
		.comparing((TeamMember member) -> !member.isLeader())
		.thenComparing(TeamMember::getJoinedAt)
		.thenComparing(TeamMember::getId);

	private final TeamRepository teamRepository;

	private final TeamMemberRepository teamMemberRepository;

	private final TeamAccess teamAccess;

	private final TeamMemberReader teamMemberReader;

	private final AccountReader accountReader;

	private final Clock clock;

	/**
	 * 만든 사람을 그 기수로 팀장으로 넣는다. 기수가 틀리면 예외로 트랜잭션이 롤백되어 팀 행도 남지 않는다.
	 * 규칙에 맞지 않으면 InvalidTeamNameException, InvalidTeamDescriptionException, InvalidGenerationException.
	 */
	@Transactional
	public TeamDetailResult createTeam(long accountId, String name, String description, Integer generation) {
		Team team = Team.createTeam(name, description, clock.instant());
		teamRepository.save(team);
		teamMemberRepository.save(TeamMember.createLeader(team.getId(), accountId, generation, team.getCreatedAt()));
		return TeamDetailResult.of(team, accountReader.nameOf(accountId), TeamRole.LEADER);
	}

	@Transactional(readOnly = true)
	public List<MyTeamResult> findMyTeams(long accountId) {
		List<TeamMember> myTeamMembers = teamMemberRepository.findAllByAccountIdOrderByJoinedAtAscIdAsc(accountId);
		Map<Long, Team> teams = teamRepository.findAllById(myTeamMembers.stream().map(TeamMember::getTeamId).toList())
			.stream()
			.collect(Collectors.toMap(Team::getId, Function.identity()));
		return myTeamMembers.stream().map(myTeamMember -> {
			Team team = teams.get(myTeamMember.getTeamId());
			return new MyTeamResult(team.getId(), team.getName(), myTeamMember.getRole());
		}).toList();
	}

	@Transactional(readOnly = true)
	public TeamDetailResult findTeamDetail(long accountId, long teamId) {
		TeamMember me = teamAccess.requireMember(teamId, accountId);
		return TeamDetailResult.of(findTeam(teamId), accountReader.nameOf(teamMemberReader.leaderIdOf(teamId)), me.getRole());
	}

	@Transactional(readOnly = true)
	public List<TeamMemberResult> findTeamMembers(long accountId, long teamId) {
		teamAccess.requireMember(teamId, accountId);
		List<TeamMember> members = teamMemberRepository.findAllByTeamId(teamId);
		Map<Long, String> names = accountReader.namesOf(members.stream().map(TeamMember::getAccountId).toList());
		return members.stream()
			.sorted(MEMBER_ORDER)
			.map(member -> TeamMemberResult.of(member, names.get(member.getAccountId())))
			.toList();
	}

	/** 자기 기수만 바꾼다. null이면 비운다. 규칙에 맞지 않으면 InvalidGenerationException. */
	@Transactional
	public TeamMemberResult changeMyGeneration(long accountId, long teamId, Integer generation) {
		TeamMember me = teamAccess.requireMember(teamId, accountId);
		me.changeGeneration(generation, clock.instant());
		return TeamMemberResult.of(me, accountReader.nameOf(accountId));
	}

	@Transactional(readOnly = true)
	public String findInviteCode(long leaderId, long teamId) {
		teamAccess.requireLeader(teamId, leaderId);
		return findTeam(teamId).getInviteCode();
	}

	/** 새 코드를 만들어 돌려준다. 이전 코드는 바로 무효가 되고, 이미 있던 대기 중인 요청은 그대로 남는다. */
	@Transactional
	public String regenerateInviteCode(long leaderId, long teamId) {
		teamAccess.requireLeader(teamId, leaderId);
		Team team = findTeam(teamId);
		team.regenerateInviteCode(clock.instant());
		return team.getInviteCode();
	}

	private Team findTeam(long teamId) {
		return teamRepository.findById(teamId).orElseThrow(TeamNotFoundException::new);
	}

}
