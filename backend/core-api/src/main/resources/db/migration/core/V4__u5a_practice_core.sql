-- U5a V4: 연습 목표 · 상황 표. V2(U2) · V3(U4a) 뒤에 적용한다. 시각은 모두 앱이 넣는다.
CREATE TABLE core.core_behavior_definition (
 id uuid PRIMARY KEY, behavior_key varchar(120) NOT NULL, version integer NOT NULL CHECK(version>0),
 display_name text NOT NULL, behavior_definition_text text NOT NULL DEFAULT '',
 opportunity_condition text NOT NULL DEFAULT '', scenario_condition text NOT NULL DEFAULT '',
 judgment_criteria text NOT NULL DEFAULT '', status varchar(20) NOT NULL CHECK(status IN('DRAFT','ACTIVE','RETIRED')),
 created_at timestamptz NOT NULL, used_at timestamptz CHECK(used_at IS NULL OR status IN('ACTIVE','RETIRED')),
 UNIQUE(behavior_key,version),
 CHECK(status<>'ACTIVE' OR (btrim(display_name)<>'' AND btrim(behavior_definition_text)<>'' AND
  btrim(opportunity_condition)<>'' AND btrim(scenario_condition)<>'' AND btrim(judgment_criteria)<>''))
);
CREATE FUNCTION core.guard_used_behavior_definition() RETURNS trigger LANGUAGE plpgsql AS $guard$
BEGIN
 IF OLD.used_at IS NOT NULL THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Used behavior definitions are immutable' USING ERRCODE='23514'; END IF;
  IF ROW(NEW.id,NEW.behavior_key,NEW.version,NEW.display_name,NEW.behavior_definition_text,
   NEW.opportunity_condition,NEW.scenario_condition,NEW.judgment_criteria,NEW.created_at,NEW.used_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.behavior_key,OLD.version,OLD.display_name,OLD.behavior_definition_text,
   OLD.opportunity_condition,OLD.scenario_condition,OLD.judgment_criteria,OLD.created_at,OLD.used_at)
   THEN RAISE EXCEPTION 'Used behavior definitions are immutable' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$guard$;
CREATE TRIGGER used_behavior_definition_immutable BEFORE UPDATE OR DELETE ON core.core_behavior_definition
 FOR EACH ROW EXECUTE FUNCTION core.guard_used_behavior_definition();

CREATE TABLE core.core_support_item (
 id uuid PRIMARY KEY, behavior_key varchar(120) NOT NULL, condition_text text NOT NULL,
 content_text text NOT NULL, limitation_text text NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN('DRAFT','APPROVED','RETIRED')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 CHECK(btrim(condition_text)<>'' AND btrim(content_text)<>'' AND btrim(limitation_text)<>'')
);
CREATE INDEX core_support_item_behavior_idx ON core.core_support_item(behavior_key,status);

CREATE TABLE core.core_goal_recommendation (
 id uuid PRIMARY KEY, child_id uuid NOT NULL REFERENCES core.core_child(id) ON DELETE RESTRICT,
 source_session_id uuid NOT NULL, structured_experience_id uuid,
 behavior_key varchar(120) NOT NULL, behavior_version integer NOT NULL,
 display_name text NOT NULL CHECK(btrim(display_name)<>''), reason_text text NOT NULL CHECK(btrim(reason_text)<>''),
 status varchar(20) NOT NULL CHECK(status IN('ACTIVE','INVALIDATED')),
 created_at timestamptz NOT NULL, invalidated_at timestamptz,
 FOREIGN KEY(behavior_key,behavior_version) REFERENCES core.core_behavior_definition(behavior_key,version) ON DELETE RESTRICT,
 UNIQUE(child_id,source_session_id,behavior_key,behavior_version),
 UNIQUE(id,child_id,behavior_key,behavior_version),
 CHECK((status='ACTIVE' AND invalidated_at IS NULL) OR(status='INVALIDATED' AND invalidated_at IS NOT NULL))
);
CREATE TABLE core.core_recommendation_evidence (
 id uuid PRIMARY KEY, recommendation_id uuid NOT NULL REFERENCES core.core_goal_recommendation(id) ON DELETE RESTRICT,
 session_id uuid NOT NULL, utterance_sequence_no bigint NOT NULL CHECK(utterance_sequence_no>0),
 evidence_type varchar(30) NOT NULL CHECK(evidence_type IN('GUARDIAN_INPUT','CHILD_RECALL')),
 created_at timestamptz NOT NULL,
 UNIQUE(recommendation_id,session_id,utterance_sequence_no,evidence_type)
);
CREATE TABLE core.core_practice_goal_selection (
 id uuid PRIMARY KEY, guardian_id uuid NOT NULL REFERENCES core.core_guardian(id) ON DELETE RESTRICT,
 child_id uuid NOT NULL REFERENCES core.core_child(id) ON DELETE RESTRICT,
 recommendation_id uuid NOT NULL, behavior_key varchar(120) NOT NULL, behavior_version integer NOT NULL,
 display_name text NOT NULL CHECK(btrim(display_name)<>''), is_favorite boolean NOT NULL DEFAULT false,
 sort_order bigint NOT NULL CHECK(sort_order>0), status varchar(20) NOT NULL CHECK(status IN('ACTIVE','DELETED')),
 selected_at timestamptz NOT NULL, deleted_at timestamptz, restore_until timestamptz,
 FOREIGN KEY(recommendation_id,child_id,behavior_key,behavior_version)
  REFERENCES core.core_goal_recommendation(id,child_id,behavior_key,behavior_version) ON DELETE RESTRICT,
 UNIQUE(child_id,recommendation_id), UNIQUE(guardian_id,sort_order),
 CHECK((status='ACTIVE' AND deleted_at IS NULL AND restore_until IS NULL) OR
       (status='DELETED' AND deleted_at IS NOT NULL AND restore_until>deleted_at))
);
CREATE INDEX core_goal_selection_list_idx ON core.core_practice_goal_selection(guardian_id,status,is_favorite DESC,sort_order);

-- Session/goal identifiers below are immutable logical references, never reads of Realtime-owned tables.
CREATE TABLE core.core_scenario (
 id uuid PRIMARY KEY, guardian_id uuid NOT NULL, child_id uuid NOT NULL, session_id uuid NOT NULL,
 goal_selection_id uuid NOT NULL, behavior_key varchar(120) NOT NULL, behavior_version integer NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN('ORIGINAL','NEW_SCENARIO')), previous_scenario_id uuid,
 relation_type text NOT NULL, opportunity_condition text NOT NULL, scenario_text text NOT NULL,
 partner_role_text text NOT NULL, trigger_script text NOT NULL, validation_json jsonb NOT NULL,
 content_fingerprint varchar(64) NOT NULL, status varchar(20) NOT NULL CHECK(status IN('ISSUED','EXPOSED')),
 exposed_session_id uuid, model_version varchar(120) NOT NULL, prompt_version varchar(120) NOT NULL,
 created_at timestamptz NOT NULL, exposed_at timestamptz,
 UNIQUE(child_id,content_fingerprint),
 CHECK((kind='ORIGINAL' AND previous_scenario_id IS NULL) OR(kind='NEW_SCENARIO' AND previous_scenario_id IS NOT NULL)),
 CHECK((status='ISSUED' AND exposed_session_id IS NULL AND exposed_at IS NULL) OR
       (status='EXPOSED' AND exposed_session_id IS NOT NULL AND exposed_at IS NOT NULL))
);
CREATE INDEX core_scenario_guardian_idx ON core.core_scenario(guardian_id);
CREATE TABLE core.core_scenario_request (
 request_key varchar(64) PRIMARY KEY, guardian_id uuid NOT NULL, child_id uuid NOT NULL,
 session_id uuid NOT NULL, goal_selection_id uuid NOT NULL, kind varchar(20) NOT NULL,
 previous_scenario_id uuid, claim_id uuid NOT NULL, lease_until timestamptz NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN('GENERATING','READY','FAILED')), result_scenario_id uuid,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 CHECK(kind IN('ORIGINAL','NEW_SCENARIO')),
 CHECK((status='READY' AND result_scenario_id IS NOT NULL) OR(status<>'READY' AND result_scenario_id IS NULL))
);
CREATE INDEX core_scenario_request_guardian_idx ON core.core_scenario_request(guardian_id);

-- Versioned demonstration references only. No real account is automatically given recommendations or selections.
INSERT INTO core.core_behavior_definition
(id,behavior_key,version,display_name,behavior_definition_text,opportunity_condition,scenario_condition,judgment_criteria,status,created_at)
VALUES
('00000000-0000-0000-0000-000000000501','ACCEPT_STOP',1,'친구가 그만하자고 할 때 받아들이기',
 '친구의 그만하자는 말을 듣고 함께 하던 활동을 멈추겠다고 말한다.','함께 놀던 친구가 그만하고 싶다고 말한다.',
 '또래 친구와 장난감이나 놀이를 함께 하는 짧은 상황','그만하자는 말을 받아들이고 활동을 멈추겠다는 반응이 있는지 관찰한다.',
 'ACTIVE','2026-10-12T00:00:00Z'),
('00000000-0000-0000-0000-000000000502','ASK_TO_JOIN',1,'함께 해도 되는지 물어보기',
 '놀이에 참여하기 전에 함께 해도 되는지 묻는다.','또래 친구들이 함께 놀이를 하고 있다.',
 '새로운 놀이에 참여하고 싶은 짧은 상황','함께 하고 싶다는 뜻을 질문으로 전하는지 관찰한다.',
 'ACTIVE','2026-10-12T00:00:00Z'),
('00000000-0000-0000-0000-000000000503','ASK_FOR_CLARIFICATION',1,'잘 모를 때 다시 물어보기',
 '들은 말의 뜻이 분명하지 않을 때 다시 설명해 달라고 요청한다.','또래 친구의 설명에 뜻을 알기 어려운 말이 나온다.',
 '놀이 방법을 설명 듣는 짧은 상황','이해하기 어려운 부분을 다시 말해 달라고 요청하는지 관찰한다.',
 'ACTIVE','2026-10-12T00:00:00Z');
INSERT INTO core.core_support_item
(id,behavior_key,condition_text,content_text,limitation_text,status,created_at,updated_at)
VALUES('00000000-0000-0000-0000-000000000511','ACCEPT_STOP','아이가 함께 놀던 일을 이야기할 때',
 '친구가 하고 싶었던 것과 아이가 하고 싶었던 것을 차례로 물어보세요.',
 '한 번의 대화만으로 다른 상황의 반응까지 알 수는 없어요.','APPROVED','2026-10-12T00:00:00Z','2026-10-12T00:00:00Z');
