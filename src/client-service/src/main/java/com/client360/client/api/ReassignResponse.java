package com.client360.client.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * {@code POST /clients/{id}/reassign} response (SPEC.md §5.3): the client representation with
 * {@code tasksTransferred} beside it, which is why the client is unwrapped rather than nested.
 *
 * @param tasksTransferred how many open tasks followed the client. Always {@code 0} today: tasks
 *     live in interaction-service, which has no code yet, so nothing can have followed. It is
 *     reported rather than omitted so the field does not appear later and change the response
 *     shape.
 */
public record ReassignResponse(@JsonUnwrapped ClientResponse client, int tasksTransferred) {}
