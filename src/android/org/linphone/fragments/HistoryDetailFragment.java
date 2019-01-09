package org.linphone.fragments;

/*
HistoryDetailFragment.java
Copyright (C) 2017  Belledonne Communications, Grenoble, France

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

import android.app.Fragment;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.linphone.LinphoneManager;
import org.linphone.LinphonePreferences;
import org.linphone.contacts.ContactsManager;
import org.linphone.contacts.LinphoneContact;
import org.linphone.LinphoneUtils;
import org.linphone.R;
import org.linphone.activities.LinphoneActivity;
import org.linphone.core.Address;
import org.linphone.core.ChatRoom;
import org.linphone.core.ChatRoomListenerStub;
import org.linphone.core.ChatRoomSecurityLevel;
import org.linphone.core.Core;
import org.linphone.core.Factory;
import org.linphone.core.PresenceModel;
import org.linphone.core.ProxyConfig;
import org.linphone.core.ZrtpPeerStatus;
import org.linphone.mediastream.Log;

import static org.linphone.LinphoneUtils.getSecurityLevelForSipUri;
import static org.linphone.LinphoneUtils.getZrtpStatus;

public class HistoryDetailFragment extends Fragment implements OnClickListener {
	private ImageView dialBack, chat, addToContacts, goToContact, back;
	private View view;
	private ImageView contactPicture, callDirection;
	private TextView contactName, contactAddress, time, date, callText;
	private String sipUri, displayName, pictureUri;
	private RelativeLayout mWaitLayout;
	private LinphoneContact contact;
	private ChatRoom mChatRoom;
	private ChatRoomListenerStub mChatRoomCreationListener;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		sipUri = getArguments().getString("SipUri");
		displayName = getArguments().getString("DisplayName");
		pictureUri = getArguments().getString("PictureUri");
		String status = getArguments().getString("Call.Status");
		String callTime = getArguments().getString("CallTime");
		String callDate = getArguments().getString("CallDate");

		view = inflater.inflate(R.layout.history_detail, container, false);

		mWaitLayout = view.findViewById(R.id.waitScreen);
		mWaitLayout.setVisibility(View.GONE);

		dialBack = (ImageView) view.findViewById(R.id.call);
		dialBack.setOnClickListener(this);

		back = (ImageView) view.findViewById(R.id.back);
		if(getResources().getBoolean(R.bool.isTablet)){
			back.setVisibility(View.INVISIBLE);
		} else {
			back.setOnClickListener(this);
		}

		chat = (ImageView) view.findViewById(R.id.chat);
		chat.setOnClickListener(this);
		if (getResources().getBoolean(R.bool.disable_chat))
			view.findViewById(R.id.chat).setVisibility(View.GONE);

		addToContacts = (ImageView) view.findViewById(R.id.add_contact);
		addToContacts.setOnClickListener(this);

		goToContact = (ImageView) view.findViewById(R.id.goto_contact);
		goToContact.setOnClickListener(this);

		contactPicture = (ImageView) view.findViewById(R.id.contact_picture);

		contactName = (TextView) view.findViewById(R.id.contact_name);
		contactAddress = (TextView) view.findViewById(R.id.contact_address);

		contactName.setText(displayName);

		callDirection = (ImageView) view.findViewById(R.id.direction);

		time = (TextView) view.findViewById(R.id.time);
		date = (TextView) view.findViewById(R.id.date);

		callText = (TextView) view.findViewById(R.id.call_text);

		// Obiane spec
		if (!status.isEmpty() && !callTime.isEmpty() && !callDate.isEmpty()) {
			displayHistory(status, callTime, callDate);
			callText.setVisibility(View.VISIBLE);
		}

		mChatRoomCreationListener = new ChatRoomListenerStub() {
			@Override
			public void onStateChanged(ChatRoom cr, ChatRoom.State newState) {
				if (newState == ChatRoom.State.Created) {
					mWaitLayout.setVisibility(View.GONE);
					LinphoneActivity.instance().goToChat(cr.getPeerAddress().asStringUriOnly(), null);
				} else if (newState == ChatRoom.State.CreationFailed) {
					mWaitLayout.setVisibility(View.GONE);
					LinphoneActivity.instance().displayChatRoomError();
					Log.e("Group chat room for address " + cr.getPeerAddress() + " has failed !");
				}
			}
		};

		return view;
	}

	@Override
	public void onPause() {
		if (mChatRoom != null) {
			mChatRoom.removeListener(mChatRoomCreationListener);
		}
		super.onPause();
	}

	private void displayHistory(String status, String callTime, String callDate) {
		if (status.equals(getResources().getString(R.string.missed))) {
			callDirection.setImageResource(R.drawable.call_missed);
		} else if (status.equals(getResources().getString(R.string.incoming))) {
			callDirection.setImageResource(R.drawable.call_incoming);
		} else if (status.equals(getResources().getString(R.string.outgoing))) {
			callDirection.setImageResource(R.drawable.call_outgoing);
		}

		time.setText(callTime == null ? "" : callTime);
		Long longDate = Long.parseLong(callDate);
		date.setText(LinphoneUtils.timestampToHumanDate(getActivity(),longDate,getString(R.string.history_detail_date_format), false));

		Address lAddress = null;
		lAddress = Factory.instance().createAddress(sipUri);

		if (lAddress != null) {
			contactAddress.setText(lAddress.asStringUriOnly());
			contact = ContactsManager.getInstance().findContactFromAddress(lAddress);
			ProxyConfig prx = LinphoneManager.getLc().getDefaultProxyConfig();
			Address ourUri = (prx != null) ? prx.getIdentityAddress() : null;
			ChatRoomSecurityLevel securityLevel = getSecurityLevelForSipUri(LinphoneManager.getLc(), ourUri, contact.getFriend().getAddress());
			if (securityLevel == ChatRoomSecurityLevel.Safe) {
				contactPicture.setImageResource(R.drawable.avatar_medium_secure2);
			} else if (securityLevel == ChatRoomSecurityLevel.Unsafe) {
				contactPicture.setImageResource(R.drawable.avatar_medium_unsecure);
			} else if (securityLevel == ChatRoomSecurityLevel.Encrypted) {
				contactPicture.setImageResource(R.drawable.avatar_medium_secure1);
			} else {
				ZrtpPeerStatus zrtpStatus = getZrtpStatus(LinphoneManager.getLc(), contact.getFriend().getAddress().asStringUriOnly());
				if (zrtpStatus == ZrtpPeerStatus.Valid) {
					contactPicture.setImageResource(R.drawable.avatar_medium_secure2);
				} else if (zrtpStatus == ZrtpPeerStatus.Invalid) {
					contactPicture.setImageResource(R.drawable.avatar_medium_unsecure);
				} else {
					if (!ContactsManager.getInstance().isContactPresenceDisabled() && contact != null && contact.getFriend() != null) {
						PresenceModel presenceModel = contact.getFriend().getPresenceModel();
						if (presenceModel != null) {
							contactPicture.setImageResource(R.drawable.avatar_medium_secure1);
						} else {
							contactPicture.setImageResource(R.drawable.avatar_medium_unregistered);
						}
					} else {
						contactPicture.setImageResource(R.drawable.avatar_medium_unregistered);
					}
				}
			}
			if (contact != null) {
				contactName.setText(contact.getFullName());
				//LinphoneUtils.setImagePictureFromUri(view.getContext(),contactPicture,contact.getPhotoUri(),contact.getThumbnailUri());
				//addToContacts.setVisibility(View.GONE);
				//goToContact.setVisibility(View.VISIBLE);
			} else {
				contactName.setText(displayName == null ? LinphoneUtils.getAddressDisplayName(sipUri) : displayName);
				//contactPicture.setImageBitmap(ContactsManager.getInstance().getDefaultAvatarBitmap());
				//addToContacts.setVisibility(View.VISIBLE);
				//goToContact.setVisibility(View.GONE);
			}
		} else {
			contactAddress.setText(sipUri);
			contactName.setText(displayName == null ? LinphoneUtils.getAddressDisplayName(sipUri) : displayName);
		}
	}

	public void changeDisplayedHistory(String sipUri, String displayName, String pictureUri, String status, String callTime, String callDate) {
		if (displayName == null ) {
			displayName = LinphoneUtils.getUsernameFromAddress(sipUri);
		}

		this.sipUri = sipUri;
		this.displayName = displayName;
		this.pictureUri = pictureUri;
		displayHistory(status, callTime, callDate);
	}

	@Override
	public void onResume() {
		super.onResume();

		if (LinphoneActivity.isInstanciated()) {
			LinphoneActivity.instance().selectMenu(FragmentsAvailable.HISTORY_DETAIL);
			LinphoneActivity.instance().hideTabBar(false);
		}
	}

	@Override
	public void onClick(View v) {
		int id = v.getId();

		if (id == R.id.back) {
			getFragmentManager().popBackStackImmediate();
		} if (id == R.id.call) {
			LinphoneActivity.instance().setAddresGoToDialerAndCall(sipUri, displayName, pictureUri == null ? null : Uri.parse(pictureUri));
		} else if (id == R.id.chat) {
			Core lc = LinphoneManager.getLc();
			Address participant = Factory.instance().createAddress(sipUri);
			ProxyConfig defaultProxyConfig = lc.getDefaultProxyConfig();
			if (defaultProxyConfig != null && defaultProxyConfig.getContact() != null) {
				ChatRoom room = lc.findOneToOneChatRoom(defaultProxyConfig.getContact(), participant);
				if (room != null) {
					LinphoneActivity.instance().goToChat(room.getPeerAddress().asStringUriOnly(), null);
				} else {
					if (defaultProxyConfig.getConferenceFactoryUri() != null && !LinphonePreferences.instance().useBasicChatRoomFor1To1()) {
						mWaitLayout.setVisibility(View.VISIBLE);
						mChatRoom = lc.createClientGroupChatRoom(getString(R.string.dummy_group_chat_subject), false);
						mChatRoom.addListener(mChatRoomCreationListener);
						Address participants[] = new Address[1];
						participants[0] = participant;
						mChatRoom.addParticipants(participants);
					} else {
						room = lc.getChatRoom(participant);
						LinphoneActivity.instance().goToChat(room.getPeerAddress().asStringUriOnly(), null);
					}
				}
			} else {
				LinphoneActivity.instance().displayCustomToast(getString(R.string.error_network_unreachable), Toast.LENGTH_LONG);
			}
		} else if (id == R.id.add_contact) {
			Address addr = Factory.instance().createAddress(sipUri);
			String uri = addr.asStringUriOnly();
			if (addr != null && addr.getDisplayName() != null)
				LinphoneActivity.instance().displayContactsForEdition(addr.asStringUriOnly(), addr.getDisplayName());
			else
				LinphoneActivity.instance().displayContactsForEdition(uri);
		} else if (id == R.id.goto_contact) {
			LinphoneActivity.instance().displayContact(contact, false);
		}
	}
}
