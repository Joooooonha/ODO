package SeSAC.Dance_Assessment.Domain;

/**
 * 영상의 용도. {@link VideoType}(기준/연습)과는 다른 축이다.
 *
 * <ul>
 *   <li>{@link #GENERAL} — 자유 연습. 본인만 볼 수 있고, 연습 기록·분석의 대상이다</li>
 *   <li>{@link #MISSION} — Mission의 레퍼런스(REFERENCE)·제출(PRACTICE) 영상.
 *       <b>분석하지 않고 연습 기록도 만들지 않는다.</b> 그 미션 팀의 활동 중 멤버가 볼 수 있다</li>
 * </ul>
 *
 * <p>{@code VideoType}에 값을 추가하지 않고 컬럼을 따로 둔 이유: 배포가 실패해 직전 jar로
 * 롤백되면, 직전 jar의 enum에 없는 값이 DB에 있는 순간 그 행을 읽다가 예외가 난다.
 * 새 컬럼은 직전 jar가 모르는 채로 지나가고, DB DEFAULT('GENERAL') 덕분에 직전 jar의
 * INSERT도 성공한다.
 */
public enum VideoPurpose {
    GENERAL,
    MISSION
}
