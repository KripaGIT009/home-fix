import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchTemplates,
  updateTemplate,
  type NotificationTemplate,
  type UpdateTemplatePayload,
} from './api';

export const notificationKeys = {
  templates: ['admin', 'notification-templates'] as const,
};

/** All notification templates (Requirement 19.2). */
export function useTemplates(): UseQueryResult<NotificationTemplate[], ApiError> {
  return useQuery<NotificationTemplate[], ApiError>({
    queryKey: notificationKeys.templates,
    queryFn: fetchTemplates,
  });
}

/** Update a notification template. */
export function useUpdateTemplate(): UseMutationResult<
  NotificationTemplate,
  ApiError,
  { id: string; payload: UpdateTemplatePayload }
> {
  const queryClient = useQueryClient();
  return useMutation<
    NotificationTemplate,
    ApiError,
    { id: string; payload: UpdateTemplatePayload }
  >({
    mutationFn: ({ id, payload }) => updateTemplate(id, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationKeys.templates });
    },
  });
}
