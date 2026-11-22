package com.glucose.patient.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/** Physical cleanup is restricted to the authenticated patient's ID. */
public interface PatientCleanupMapper {
    @Delete("DELETE m FROM qa_message m JOIN qa_session s ON m.session_id=s.id WHERE s.patient_id=#{id}")
    int messages(@Param("id") long id);
    @Delete("DELETE FROM qa_session WHERE patient_id=#{id}") int sessions(@Param("id") long id);
    @Delete("DELETE FROM glucose_record WHERE patient_id=#{id}") int records(@Param("id") long id);
    @Delete("DELETE FROM glucose_import_batch WHERE patient_id=#{id}") int batches(@Param("id") long id);
    @Delete("DELETE FROM alert_record WHERE patient_id=#{id}") int alerts(@Param("id") long id);
    @Delete("DELETE FROM alert_rule WHERE patient_id=#{id}") int rules(@Param("id") long id);
    @Delete("DELETE FROM glucose_target_rule WHERE patient_id=#{id}") int targets(@Param("id") long id);
    @Delete("DELETE FROM medication_log WHERE patient_id=#{id}") int medications(@Param("id") long id);
    @Delete("DELETE FROM medication_plan WHERE patient_id=#{id}") int medicationPlans(@Param("id") long id);
    @Delete("DELETE FROM diet_log WHERE patient_id=#{id}") int diets(@Param("id") long id);
    @Delete("DELETE FROM patient_profile WHERE user_id=#{id}") int profile(@Param("id") long id);
}
