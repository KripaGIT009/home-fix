-- Staff resolution note recorded from the Admin Portal (PATCH /admin/complaints/{id}).
-- Nullable: complaints resolved before this column existed, and every complaint that has not
-- been resolved from the portal, carry no note.
ALTER TABLE complaint.complaint ADD COLUMN resolution_note character varying(2000);
